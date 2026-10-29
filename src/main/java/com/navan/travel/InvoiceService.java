
package com.navan.travel;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final TransactionRepository transactionRepository;

    public InvoiceService(
            InvoiceRepository invoiceRepository,
            TransactionRepository transactionRepository) {
        this.invoiceRepository = invoiceRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public long createInvoice(List<Long> transactionIds) {
        if (transactionIds == null || transactionIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one transaction ID is required");
        }

        if (transactionIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException(
                    "Transaction IDs must be positive");
        }

        if (transactionIds.stream().distinct().count() != transactionIds.size()) {
            throw new IllegalArgumentException(
                    "Duplicate transaction IDs are not allowed");
        }

        List<TransactionRepository.TransactionRecord> transactions =
                transactionIds.stream()
                        .map(id -> transactionRepository.findById(id)
                                .orElseThrow(() -> new IllegalArgumentException(
                                        "Transaction not found: " + id)))
                        .toList();

        String currency = transactions.get(0).currency();

        if (transactions.stream()
                .anyMatch(transaction ->
                        !currency.equals(transaction.currency()))) {
            throw new IllegalStateException(
                    "All transactions in an invoice must use the same currency");
        }

        BigDecimal total = transactions.stream()
                .map(TransactionRepository.TransactionRecord::total)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long invoiceId = invoiceRepository.insertInvoice(currency, total);

        for (TransactionRepository.TransactionRecord transaction : transactions) {
            long snapshotId = invoiceRepository.insertTransactionSnapshot(
                    invoiceId, transaction);

            for (TransactionLine.TaxLine taxLine :
                    transactionRepository.findTaxLines(transaction.id())) {
                invoiceRepository.insertTaxSnapshot(snapshotId, taxLine);
            }

            for (TransactionLine.FeeLine feeLine :
                    transactionRepository.findFeeLines(transaction.id())) {
                invoiceRepository.insertFeeSnapshot(snapshotId, feeLine);
            }
        }

        return invoiceId;
    }

    public InvoiceRepository.InvoiceRecord findById(long id) {
        return invoiceRepository.findById(id)
                .orElseThrow(() ->
                        new IllegalArgumentException("Invoice not found: " + id));
    }

    public List<InvoiceRepository.InvoiceLineRecord> findTransactionSnapshots(
            long invoiceId) {
        return invoiceRepository.findTransactionSnapshots(invoiceId);
    }

    public List<TransactionLine.TaxLine> findTaxSnapshots(long invoiceLineId) {
        return invoiceRepository.findTaxSnapshots(invoiceLineId);
    }

    public List<TransactionLine.FeeLine> findFeeSnapshots(long invoiceLineId) {
        return invoiceRepository.findFeeSnapshots(invoiceLineId);
    }
    public List<InvoiceRepository.InvoiceRecord> findPage(
        int limit, int offset) {
    return invoiceRepository.findPage(limit, offset);
    }

    public long count() {
        return invoiceRepository.count();
}
@Transactional
public void replaceInvoiceTransactions(long invoiceId, List<Long> transactionIds) {
    if (invoiceRepository.findById(invoiceId).isEmpty()) {
        throw new IllegalArgumentException("Invoice not found: " + invoiceId);
    }

    if (transactionIds == null || transactionIds.isEmpty()) {
        throw new IllegalArgumentException(
                "At least one transaction ID is required");
    }

    if (transactionIds.stream().anyMatch(id -> id == null || id <= 0)) {
        throw new IllegalArgumentException(
                "Transaction IDs must be positive");
    }

    if (transactionIds.stream().distinct().count() != transactionIds.size()) {
        throw new IllegalArgumentException(
                "Duplicate transaction IDs are not allowed");
    }

    List<TransactionRepository.TransactionRecord> transactions =
            transactionIds.stream()
                    .map(id -> transactionRepository.findById(id)
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "Transaction not found: " + id)))
                    .toList();

    String currency = transactions.get(0).currency();

    if (transactions.stream()
            .anyMatch(transaction -> !currency.equals(transaction.currency()))) {
        throw new IllegalStateException(
                "All transactions in an invoice must use the same currency");
    }

    BigDecimal total = transactions.stream()
            .map(TransactionRepository.TransactionRecord::total)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    invoiceRepository.deleteTransactionSnapshots(invoiceId);
    invoiceRepository.updateInvoice(invoiceId, currency, total);

    for (TransactionRepository.TransactionRecord transaction : transactions) {
        long snapshotId =
                invoiceRepository.insertTransactionSnapshot(invoiceId, transaction);

        for (TransactionLine.TaxLine taxLine :
                transactionRepository.findTaxLines(transaction.id())) {
            invoiceRepository.insertTaxSnapshot(snapshotId, taxLine);
        }

        for (TransactionLine.FeeLine feeLine :
                transactionRepository.findFeeLines(transaction.id())) {
            invoiceRepository.insertFeeSnapshot(snapshotId, feeLine);
        }
    }
}
@Transactional
public void deleteInvoice(long id) {
    if (invoiceRepository.findById(id).isEmpty()) {
        throw new IllegalArgumentException("Invoice not found: " + id);
    }

    invoiceRepository.deleteInvoice(id);
}
public List<TaxRollup> taxRollups(long invoiceId) {
    java.util.Map<String, TaxRollup> totals = new java.util.LinkedHashMap<>();

    for (InvoiceRepository.InvoiceLineRecord line :
            invoiceRepository.findTransactionSnapshots(invoiceId)) {
        for (TransactionLine.TaxLine tax :
                invoiceRepository.findTaxSnapshots(line.id())) {

            String key = tax.name() + "|" + tax.rate();
            TaxRollup existing = totals.get(key);

            if (existing == null) {
                totals.put(key, new TaxRollup(
                        tax.name(), tax.rate(), tax.amount()));
            } else {
                totals.put(key, new TaxRollup(
                        existing.name(),
                        existing.rate(),
                        existing.amount().add(tax.amount())));
            }
        }
    }

    return List.copyOf(totals.values());
}

public List<FeeRollup> feeRollups(long invoiceId) {
    java.util.Map<String, FeeRollup> totals = new java.util.LinkedHashMap<>();

    for (InvoiceRepository.InvoiceLineRecord line :
            invoiceRepository.findTransactionSnapshots(invoiceId)) {
        for (TransactionLine.FeeLine fee :
                invoiceRepository.findFeeSnapshots(line.id())) {

            FeeRollup existing = totals.get(fee.name());

            if (existing == null) {
                totals.put(fee.name(), new FeeRollup(
                        fee.name(), fee.amount()));
            } else {
                totals.put(fee.name(), new FeeRollup(
                        existing.name(),
                        existing.amount().add(fee.amount())));
            }
        }
    }

    return List.copyOf(totals.values());
}

public record TaxRollup(
        String name,
        BigDecimal rate,
        BigDecimal amount) {
}

public record FeeRollup(
        String name,
        BigDecimal amount) {
}
}
