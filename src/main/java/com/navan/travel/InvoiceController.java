package com.navan.travel;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/invoices")
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final ObjectMapper objectMapper;

    public InvoiceController(
            InvoiceService invoiceService,
            ObjectMapper objectMapper) {
        this.invoiceService = invoiceService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateInvoiceRequest request) {
        try {
            List<Long> transactionIds = request.transaction_ids();

            if (transactionIds == null && request.transaction_id() != null) {
                transactionIds = List.of(request.transaction_id());
            }

            long invoiceId = invoiceService.createInvoice(transactionIds);

            return ResponseEntity.status(HttpStatus.CREATED).body(
                    Map.of("id", invoiceId));

        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    Map.of("error", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
        if (ex.getMessage() != null
                && ex.getMessage().startsWith("Transaction not found:")) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    Map.of("error", ex.getMessage()));
        }

        return ResponseEntity.badRequest().body(
                Map.of("error", ex.getMessage()));
    }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable long id) {
        try {
            InvoiceRepository.InvoiceRecord invoice =
                    invoiceService.findById(id);

            List<InvoiceLineResponse> lines =
                    invoiceService.findTransactionSnapshots(id).stream()
                            .map(line -> new InvoiceLineResponse(
                                    line.transactionId(),
                                    line.externalId(),
                                    line.type(),
                                    line.currency(),
                                    line.occurredAt(),
                                    line.total(),
                                    parseMetadata(line.metadataJson()),
                                    invoiceService.findTaxSnapshots(line.id()),
                                    invoiceService.findFeeSnapshots(line.id())))
                            .toList();

        return ResponseEntity.ok(new InvoiceResponse(
            invoice.id(),
            invoice.currency(),
            invoice.total(),
            invoice.createdAt(),
            lines,
            invoiceService.taxRollups(id),
            invoiceService.feeRollups(id)));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    private Object parseMetadata(String metadataJson) {
        try {
            return objectMapper.readValue(metadataJson, Object.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException(
                    "Stored invoice metadata is invalid JSON", ex);
        }
    }

    public record CreateInvoiceRequest(
            Long transaction_id,
            List<Long> transaction_ids) {
    }

    public record InvoiceResponse(
        long id,
        String currency,
        BigDecimal total,
        String created_at,
        List<InvoiceLineResponse> transactions,
        List<InvoiceService.TaxRollup> tax_rollups,
        List<InvoiceService.FeeRollup> fee_rollups) {
}

    public record InvoiceLineResponse(
            long transaction_id,
            String external_id,
            String type,
            String currency,
            String occurred_at,
            BigDecimal total,
            Object metadata,
            List<TransactionLine.TaxLine> tax_lines,
            List<TransactionLine.FeeLine> fee_lines) {
    }
    @GetMapping
public ResponseEntity<?> list(
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(defaultValue = "0") int offset) {

    if (limit < 1 || offset < 0) {
        return ResponseEntity.badRequest().body(
                Map.of("error",
                        "limit must be positive and offset non-negative"));
    }

    int cappedLimit = Math.min(limit, 100);

    List<InvoiceRepository.InvoiceRecord> invoices =
            invoiceService.findPage(cappedLimit, offset);

    return ResponseEntity.ok(Map.of(
        "invoices", invoices,
        "limit", cappedLimit,
        "offset", offset,
        "total", invoiceService.count()));
}

@org.springframework.web.bind.annotation.PatchMapping("/{id}")
public ResponseEntity<?> update(
        @PathVariable long id,
        @RequestBody CreateInvoiceRequest request) {

    try {
        List<Long> transactionIds = request.transaction_ids();

        if (transactionIds == null && request.transaction_id() != null) {
            transactionIds = List.of(request.transaction_id());
        }

        invoiceService.replaceInvoiceTransactions(id, transactionIds);

        return getById(id);

    } catch (IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                Map.of("error", ex.getMessage()));
    } catch (IllegalArgumentException ex) {
        if (ex.getMessage() != null
                && (ex.getMessage().startsWith("Invoice not found:")
                        || ex.getMessage().startsWith("Transaction not found:"))) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    Map.of("error", ex.getMessage()));
        }

        return ResponseEntity.badRequest().body(
                Map.of("error", ex.getMessage()));
    }
}

@org.springframework.web.bind.annotation.DeleteMapping("/{id}")
public ResponseEntity<?> delete(@PathVariable long id) {
    try {
        invoiceService.deleteInvoice(id);
        return ResponseEntity.noContent().build();
    } catch (IllegalArgumentException ex) {
        return ResponseEntity.notFound().build();
    }
}
}
