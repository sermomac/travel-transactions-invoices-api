package com.navan.travel;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;


@Service
public class TransactionService {

    private static final Set<String> VALID_TYPES =
            Set.of("flight", "hotel", "rail", "navan_fee");

    private final TransactionRepository transactionRepository;
    private final ObjectMapper objectMapper;

    public TransactionService(
        TransactionRepository transactionRepository,
        ObjectMapper objectMapper) {
    this.transactionRepository = transactionRepository;
    this.objectMapper = objectMapper;
}

    @Transactional
    public long createTransaction(
            String externalId,
            String type,
            String currency,
            String occurredAt,
            BigDecimal total,
            String metadataJson,
            List<TransactionLine.TaxLine> taxLines,
            List<TransactionLine.FeeLine> feeLines) {

        validateRequired(externalId, "external_id");
        validateRequired(type, "type");
        validateRequired(currency, "currency");
        validateRequired(occurredAt, "occurred_at");

        if (taxLines == null) {
            throw new IllegalArgumentException("tax_lines is required");
        }

        if (feeLines == null) {
            throw new IllegalArgumentException("fee_lines is required");
        }

        if (!VALID_TYPES.contains(type)) {
            throw new IllegalArgumentException(
                    "Unsupported transaction type: " + type);
        }
        if (transactionRepository.findByExternalId(externalId).isPresent()) {
            throw new DuplicateKeyException(
                   "Transaction external_id already exists: " + externalId);
}
        if (total == null || total.signum() < 0) {
            throw new IllegalArgumentException(
                    "total must be zero or greater");
        }

        validateMetadata(type, metadataJson);

        long transactionId = transactionRepository.insert(
                externalId, type, currency, occurredAt, total, metadataJson);

        if (taxLines != null) {
            for (TransactionLine.TaxLine taxLine : taxLines) {
                if (taxLine == null || taxLine.name() == null
                        || taxLine.name().isBlank()
                        || taxLine.amount() == null) {
                    throw new IllegalArgumentException("Invalid tax line");
                }
                transactionRepository.insertTaxLine(transactionId, taxLine);
            }
        }

        if (feeLines != null) {
            for (TransactionLine.FeeLine feeLine : feeLines) {
                if (feeLine == null || feeLine.name() == null
                        || feeLine.name().isBlank()
                        || feeLine.amount() == null) {
                    throw new IllegalArgumentException("Invalid fee line");
                }
                transactionRepository.insertFeeLine(transactionId, feeLine);
            }
        }

        return transactionId;
    }

    public Optional<TransactionRepository.TransactionRecord> findById(long id) {
        return transactionRepository.findById(id);
    }
    public Optional<TransactionRepository.TransactionRecord> findByExternalId(
        String externalId) {
    return transactionRepository.findByExternalId(externalId);
    }
    public List<TransactionLine.TaxLine> findTaxLines(long transactionId) {
        return transactionRepository.findTaxLines(transactionId);
    }

    public List<TransactionLine.FeeLine> findFeeLines(long transactionId) {
        return transactionRepository.findFeeLines(transactionId);
    }

    public List<TransactionRepository.TransactionRecord> findPage(
        int limit, int offset) {
    return transactionRepository.findPage(limit, offset);
    }

    public long count() {
        return transactionRepository.count();
    }


    @Transactional
    public synchronized void updateTransaction(
            long id,
            String type,
            String currency,
            String occurredAt,
            BigDecimal total,
            String metadataJson,
            List<TransactionLine.TaxLine> taxLines,
            List<TransactionLine.FeeLine> feeLines) {

        if (transactionRepository.findById(id).isEmpty()) {
            throw new IllegalArgumentException("Transaction not found: " + id);
        }

        if (transactionRepository.isUsedByInvoice(id)) {
            throw new IllegalStateException(
                    "Transaction is included in an invoice and cannot be updated");
        }

        validateRequired(type, "type");
        validateRequired(currency, "currency");
        validateRequired(occurredAt, "occurred_at");

        if (!VALID_TYPES.contains(type)) {
            throw new IllegalArgumentException(
                    "Unsupported transaction type: " + type);
        }

        if (total == null || total.signum() < 0) {
            throw new IllegalArgumentException(
                    "total must be zero or greater");
        }

       validateMetadata(type, metadataJson);

        transactionRepository.update(
                id, type, currency, occurredAt, total, metadataJson);

        transactionRepository.deleteTaxLines(id);
        transactionRepository.deleteFeeLines(id);

        if (taxLines != null) {
            for (TransactionLine.TaxLine taxLine : taxLines) {
                if (taxLine == null || taxLine.name() == null
                        || taxLine.name().isBlank()
                        || taxLine.amount() == null
                        || taxLine.amount().signum() < 0
                        || (taxLine.rate() != null && taxLine.rate().signum() < 0)) {
                    throw new IllegalArgumentException("Invalid tax line");

                }
                transactionRepository.insertTaxLine(id, taxLine);
            }
        }

        if (feeLines != null) {
            for (TransactionLine.FeeLine feeLine : feeLines) {
                if (feeLine == null || feeLine.name() == null
                        || feeLine.name().isBlank()
                        || feeLine.amount() == null
                        || feeLine.amount().signum() < 0) {
                    throw new IllegalArgumentException("Invalid fee line");
                }
                transactionRepository.insertFeeLine(id, feeLine);
            }
        }
    }

    @Transactional
    public synchronized void deleteTransaction(long id) {
        if (transactionRepository.findById(id).isEmpty()) {
            throw new IllegalArgumentException("Transaction not found: " + id);
        }

        if (transactionRepository.isUsedByInvoice(id)) {
            throw new IllegalStateException(
                    "Transaction is included in an invoice and cannot be deleted");
        }

        transactionRepository.deleteTaxLines(id);
        transactionRepository.deleteFeeLines(id);
        transactionRepository.delete(id);
    }

    private void validateMetadata(String type, String metadataJson) {
    if (metadataJson == null || metadataJson.isBlank()) {
        throw new IllegalArgumentException("metadata must not be empty");
    }

    Map<String, Object> metadata;

    try {
        metadata = objectMapper.readValue(
                metadataJson,
                objectMapper.getTypeFactory()
                        .constructMapType(Map.class, String.class, Object.class));
    } catch (JacksonException ex) {
        throw new IllegalArgumentException("metadata must be a JSON object");
    }

    if (metadata == null || metadata.isEmpty()) {
        throw new IllegalArgumentException("metadata must not be empty");
    }

    switch (type) {
        case "flight" -> {
            requireMetadata(metadata, "origin");
            requireMetadata(metadata, "destination");
            requireMetadata(metadata, "fare");

            Object coupons = metadata.get("coupons");
            if (!(coupons instanceof List<?> list) || list.isEmpty()) {
                throw new IllegalArgumentException(
                        "flight metadata must contain a non-empty coupons list");
            }

            for (Object coupon : list) {
                if (!(coupon instanceof Map<?, ?> fields)) {
                    throw new IllegalArgumentException(
                            "each coupon must be an object");
                }
                requireMetadata(fields, "flight_number");
                requireMetadata(fields, "from");
                requireMetadata(fields, "to");
                requireMetadata(fields, "departure");
                requireMetadata(fields, "arrival");
            }
        }
        case "hotel" -> {
            requireMetadata(metadata, "name");
            requireMetadata(metadata, "address");
            requireMetadata(metadata, "check_in");
            requireMetadata(metadata, "check_out");
            requireMetadata(metadata, "nights");
            requireMetadata(metadata, "room_rate_per_night");

            Object address = metadata.get("address");
            if (!(address instanceof Map<?, ?> fields)) {
                throw new IllegalArgumentException(
                        "hotel address must be an object");
            }
            requireMetadata(fields, "street");
            requireMetadata(fields, "city");
            requireMetadata(fields, "country");
        }
        case "rail" -> {
            Object legs = metadata.get("legs");
            if (!(legs instanceof List<?> list) || list.isEmpty()) {
                throw new IllegalArgumentException(
                        "rail metadata must contain a non-empty legs list");
            }

            for (Object leg : list) {
                if (!(leg instanceof Map<?, ?> fields)) {
                    throw new IllegalArgumentException(
                            "each rail leg must be an object");
                }
                requireMetadata(fields, "origin");
                requireMetadata(fields, "destination");
                requireMetadata(fields, "departure");
                requireMetadata(fields, "arrival");
            }
        }
        case "navan_fee" -> {
            Object feeKind = metadata.get("fee_kind");
            if (!(feeKind instanceof String value)
                    || !Set.of("trip_fee", "agent_call_fee").contains(value)) {
                throw new IllegalArgumentException(
                        "fee_kind must be trip_fee or agent_call_fee");
            }
        }
        default -> throw new IllegalArgumentException(
                "Unsupported transaction type: " + type);
    }
}

    private void requireMetadata(Map<?, ?> metadata, String field) {
    Object value = metadata.get(field);

    if (value == null
            || (value instanceof String text && text.isBlank())) {
        throw new IllegalArgumentException(
                "metadata field '" + field + "' is required");
    }
}

    private void validateRequired(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
