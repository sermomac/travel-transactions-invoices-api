package com.navan.travel;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class InvoiceRepository {

    private final JdbcTemplate jdbcTemplate;

    public InvoiceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insertInvoice(String currency, BigDecimal total) {
        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO invoices (currency, total) VALUES (?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, currency);
            statement.setBigDecimal(2, total);
            return statement;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException(
                    "Database did not return the generated invoice ID");
        }

        return key.longValue();
    }

    public long insertTransactionSnapshot(
            long invoiceId,
            TransactionRepository.TransactionRecord transaction) {

        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(
                    """
                    INSERT INTO invoice_transaction_snapshots
                        (invoice_id, transaction_id, external_id, type,
                         currency, occurred_at, total, metadata_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    java.sql.Statement.RETURN_GENERATED_KEYS);

            statement.setLong(1, invoiceId);
            statement.setLong(2, transaction.id());
            statement.setString(3, transaction.externalId());
            statement.setString(4, transaction.type());
            statement.setString(5, transaction.currency());
            statement.setString(6, transaction.occurredAt());
            statement.setBigDecimal(7, transaction.total());
            statement.setString(8, transaction.metadataJson());

            return statement;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException(
                    "Database did not return the invoice snapshot ID");
        }

        return key.longValue();
    }

    public void insertTaxSnapshot(
            long invoiceLineId,
            TransactionLine.TaxLine taxLine) {

        jdbcTemplate.update(
                """
                INSERT INTO invoice_tax_snapshots
                    (invoice_line_id, name, rate, amount)
                VALUES (?, ?, ?, ?)
                """,
                invoiceLineId,
                taxLine.name(),
                taxLine.rate(),
                taxLine.amount());
    }

    public void insertFeeSnapshot(
            long invoiceLineId,
            TransactionLine.FeeLine feeLine) {

        jdbcTemplate.update(
                """
                INSERT INTO invoice_fee_snapshots
                    (invoice_line_id, name, amount)
                VALUES (?, ?, ?)
                """,
                invoiceLineId,
                feeLine.name(),
                feeLine.amount());
    }

    public Optional<InvoiceRecord> findById(long id) {
        return jdbcTemplate.query(
                """
                SELECT id, currency, total, created_at
                FROM invoices
                WHERE id = ?
                """,
                (rs, rowNum) -> new InvoiceRecord(
                        rs.getLong("id"),
                        rs.getString("currency"),
                        rs.getBigDecimal("total"),
                        rs.getString("created_at")),
                id).stream().findFirst();
    }

    public List<InvoiceLineRecord> findTransactionSnapshots(long invoiceId) {
        return jdbcTemplate.query(
                """
                SELECT id, transaction_id, external_id, type, currency,
                       occurred_at, total, metadata_json
                FROM invoice_transaction_snapshots
                WHERE invoice_id = ?
                ORDER BY id
                """,
                (rs, rowNum) -> new InvoiceLineRecord(
                        rs.getLong("id"),
                        rs.getLong("transaction_id"),
                        rs.getString("external_id"),
                        rs.getString("type"),
                        rs.getString("currency"),
                        rs.getString("occurred_at"),
                        rs.getBigDecimal("total"),
                        rs.getString("metadata_json")),
                invoiceId);
    }

    public List<TransactionLine.TaxLine> findTaxSnapshots(long invoiceLineId) {
        return jdbcTemplate.query(
                """
                SELECT name, rate, amount
                FROM invoice_tax_snapshots
                WHERE invoice_line_id = ?
                ORDER BY id
                """,
                (rs, rowNum) -> new TransactionLine.TaxLine(
                        rs.getString("name"),
                        rs.getBigDecimal("rate"),
                        rs.getBigDecimal("amount")),
                invoiceLineId);
    }

    public List<TransactionLine.FeeLine> findFeeSnapshots(long invoiceLineId) {
        return jdbcTemplate.query(
                """
                SELECT name, amount
                FROM invoice_fee_snapshots
                WHERE invoice_line_id = ?
                ORDER BY id
                """,
                (rs, rowNum) -> new TransactionLine.FeeLine(
                        rs.getString("name"),
                        rs.getBigDecimal("amount")),
                invoiceLineId);
    }

    public void updateInvoice(long invoiceId, String currency, BigDecimal total) {
    jdbcTemplate.update(
            "UPDATE invoices SET currency = ?, total = ? WHERE id = ?",
            currency, total, invoiceId);
}

public void deleteTransactionSnapshots(long invoiceId) {
    jdbcTemplate.update(
            "DELETE FROM invoice_transaction_snapshots WHERE invoice_id = ?",
            invoiceId);
}
    public List<InvoiceRecord> findPage(int limit, int offset) {
    return jdbcTemplate.query(
            """
            SELECT id, currency, total, created_at
            FROM invoices
            ORDER BY id
            LIMIT ? OFFSET ?
            """,
            (rs, rowNum) -> new InvoiceRecord(
                    rs.getLong("id"),
                    rs.getString("currency"),
                    rs.getBigDecimal("total"),
                    rs.getString("created_at")),
            limit,
            offset);
    }

    public long count() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoices",
                Long.class);
        return count == null ? 0 : count;
    }

    public record InvoiceRecord(
            long id,
            String currency,
            BigDecimal total,
            String createdAt) {
    }

    public record InvoiceLineRecord(
            long id,
            long transactionId,
            String externalId,
            String type,
            String currency,
            String occurredAt,
            BigDecimal total,
            String metadataJson) {
    }
    public void deleteInvoice(long id) {
    jdbcTemplate.update("DELETE FROM invoices WHERE id = ?", id);
}
}
