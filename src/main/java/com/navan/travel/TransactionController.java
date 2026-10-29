
package com.navan.travel;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/transactions")
public class TransactionController {

    private final TransactionService transactionService;
    private final ObjectMapper objectMapper;

    public TransactionController(
            TransactionService transactionService,
            ObjectMapper objectMapper) {
        this.transactionService = transactionService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public synchronized ResponseEntity<?> create(@RequestBody CreateTransactionRequest request) {
        try {
            long id = transactionService.createTransaction(
                    request.external_id(),
                    request.type(),
                    request.currency(),
                    request.occurred_at(),
                    request.total(),
                    serializeMetadata(request.metadata()),
                    request.tax_lines(),
                    request.fee_lines()
            );

            return ResponseEntity.status(HttpStatus.CREATED)
                .body(getById(id).getBody());
            } catch (DuplicateKeyException ex) {
                return transactionService.findByExternalId(request.external_id())
                    .<ResponseEntity<?>>map(existing ->
                        ResponseEntity.status(HttpStatus.CONFLICT).body(
                            Map.of(
                                    "error", "external_id already exists",
                                    "id", existing.id(),
                                    "external_id", existing.externalId()
                            )))
                    .orElseGet(() ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(
                            Map.of("error", "external_id already exists")));
} catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", ex.getMessage())
            );
        }
    }

    private String serializeMetadata(Map<String, Object> metadata) {
    if (metadata == null) {
        return null;
    }

    try {
        return objectMapper.writeValueAsString(metadata);
    } catch (JacksonException ex) {
        throw new IllegalArgumentException("Invalid metadata JSON");
    }
}


    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable long id) {
        Optional<TransactionRepository.TransactionRecord> result =
                transactionService.findById(id);

        if (result.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        TransactionRepository.TransactionRecord transaction = result.get();

        return ResponseEntity.ok(new TransactionResponse(
                transaction.id(),
                transaction.externalId(),
                transaction.type(),
                transaction.currency(),
                transaction.occurredAt(),
                transaction.total(),
                parseMetadata(transaction.metadataJson()),
                transactionService.findTaxLines(id),
                transactionService.findFeeLines(id)
        ));
    }

    private Object parseMetadata(String metadataJson) {
        if (metadataJson == null) {
            return null;
        }

        try {
            return objectMapper.readValue(metadataJson, Object.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Stored metadata is invalid JSON", ex);
        }
    }

    public record TransactionResponse(
            long id,
            String external_id,
            String type,
            String currency,
            String occurred_at,
            BigDecimal total,
            Object metadata,
            List<TransactionLine.TaxLine> tax_lines,
            List<TransactionLine.FeeLine> fee_lines
    ) {}


    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        if (limit < 1 || offset < 0) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "limit must be positive and offset non-negative"));
        }

        int cappedLimit = Math.min(limit, 100);

        List<TransactionResponse> transactions =
                transactionService.findPage(cappedLimit, offset)
                        .stream()
                        .map(transaction -> new TransactionResponse(
                                transaction.id(),
                                transaction.externalId(),
                                transaction.type(),
                                transaction.currency(),
                                transaction.occurredAt(),
                                transaction.total(),
                                parseMetadata(transaction.metadataJson()),
                                transactionService.findTaxLines(transaction.id()),
                                transactionService.findFeeLines(transaction.id())))
                        .toList();

        return ResponseEntity.ok(Map.of(
                "transactions", transactions,
                "limit", cappedLimit,
                "offset", offset,
                "total", transactionService.count()));
    }


    public record CreateTransactionRequest(
            String external_id,
            String type,
            String currency,
            String occurred_at,
            BigDecimal total,
            List<TransactionLine.TaxLine> tax_lines,
            List<TransactionLine.FeeLine> fee_lines,
            Map<String, Object> metadata
    ) {
    }


    @PatchMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable long id,
            @RequestBody CreateTransactionRequest request) {
        try {
            transactionService.updateTransaction(
                    id,
                    request.type(),
                    request.currency(),
                    request.occurred_at(),
                    request.total(),
                    serializeMetadata(request.metadata()),
                    request.tax_lines(),
                    request.fee_lines());

            return getById(id);
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    Map.of("error", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null
                    && ex.getMessage().startsWith("Transaction not found:")) {
                return ResponseEntity.notFound().build();
        }

    return ResponseEntity.badRequest().body(
            Map.of("error", ex.getMessage()));
}
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable long id) {
        try {
            transactionService.deleteTransaction(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    Map.of("error", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

}
