
package com.navan.travel;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class TransactionRepository {

    private final JdbcTemplate jdbcTemplate;

    public TransactionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(
            String externalId,
            String type,
            String currency,
            String occurredAt,
            BigDecimal total,
            String metadataJson) {

        String sql = """
                INSERT INTO transactions
                    (external_id, type, currency, occurred_at, total, metadata_json)
                VALUES (?, ?, ?, ?, ?, ?)
                """;

        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    sql, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, externalId);
            statement.setString(2, type);
            statement.setString(3, currency);
            statement.setString(4, occurredAt);
            statement.setBigDecimal(5, total);
            statement.setString(6, metadataJson);
            return statement;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException(
                    "Database did not return the generated transaction ID");
        }

        return key.longValue();
    }


    public List<TransactionLine.TaxLine> findTaxLines(long transactionId) {
        return jdbcTemplate.query(
                """
                SELECT name, rate, amount
                FROM tax_lines
                WHERE transaction_id = ?
                ORDER BY id
                """,
                (rs, rowNum) -> new TransactionLine.TaxLine(
                        rs.getString("name"),
                        rs.getBigDecimal("rate"),
                        rs.getBigDecimal("amount")),
                transactionId);
    }

    public List<TransactionLine.FeeLine> findFeeLines(long transactionId) {
        return jdbcTemplate.query(
                """
                SELECT name, amount
                FROM fee_lines
                WHERE transaction_id = ?
                ORDER BY id
                """,
                (rs, rowNum) -> new TransactionLine.FeeLine(
                        rs.getString("name"),
                        rs.getBigDecimal("amount")),
                transactionId);
    }


    public Optional<TransactionRecord> findById(long id) {
        return jdbcTemplate.query(
                """
                SELECT id, external_id, type, currency, occurred_at,
                       total, metadata_json
                FROM transactions
                WHERE id = ?
                """,
                (rs, rowNum) -> new TransactionRecord(
                        rs.getLong("id"),
                        rs.getString("external_id"),
                        rs.getString("type"),
                        rs.getString("currency"),
                        rs.getString("occurred_at"),
                        rs.getBigDecimal("total"),
                        rs.getString("metadata_json")),
                id
        ).stream().findFirst();
    }

    public Optional<TransactionRecord> findByExternalId(String externalId) {
        return jdbcTemplate.query(
                """
                SELECT id, external_id, type, currency, occurred_at,
                       total, metadata_json
                FROM transactions
                WHERE external_id = ?
                """,
                (rs, rowNum) -> new TransactionRecord(
                        rs.getLong("id"),
                        rs.getString("external_id"),
                        rs.getString("type"),
                        rs.getString("currency"),
                        rs.getString("occurred_at"),
                        rs.getBigDecimal("total"),
                        rs.getString("metadata_json")),
                externalId
        ).stream().findFirst();
    }

    public void insertTaxLine(
            long transactionId,
            TransactionLine.TaxLine taxLine) {

        jdbcTemplate.update(
                """
                INSERT INTO tax_lines (transaction_id, name, rate, amount)
                VALUES (?, ?, ?, ?)
                """,
                transactionId,
                taxLine.name(),
                taxLine.rate(),
                taxLine.amount());
    }

    public void insertFeeLine(
            long transactionId,
            TransactionLine.FeeLine feeLine) {

        jdbcTemplate.update(
                """
                INSERT INTO fee_lines (transaction_id, name, amount)
                VALUES (?, ?, ?)
                """,
                transactionId,
                feeLine.name(),
                feeLine.amount());
    }


    public List<TransactionRecord> findPage(int limit, int offset) {
        return jdbcTemplate.query(
                """
                SELECT id, external_id, type, currency, occurred_at,
                       total, metadata_json
                FROM transactions
                ORDER BY id
                LIMIT ? OFFSET ?
                """,
                (rs, rowNum) -> new TransactionRecord(
                        rs.getLong("id"),
                        rs.getString("external_id"),
                        rs.getString("type"),
                        rs.getString("currency"),
                        rs.getString("occurred_at"),
                        rs.getBigDecimal("total"),
                        rs.getString("metadata_json")),
                limit,
                offset);
    }

    public long count() {
        Long result = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions",
                Long.class);
        return result == null ? 0L : result;
    }


    public boolean isUsedByInvoice(long transactionId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM invoice_transaction_snapshots
                WHERE transaction_id = ?
                """,
                Integer.class,
                transactionId);

        return count != null && count > 0;
    }

    public void update(
            long id,
            String type,
            String currency,
            String occurredAt,
            BigDecimal total,
            String metadataJson) {

        jdbcTemplate.update(
                """
                UPDATE transactions
                SET type = ?, currency = ?, occurred_at = ?,
                    total = ?, metadata_json = ?
                WHERE id = ?
                """,
                type, currency, occurredAt, total, metadataJson, id);
    }

    public void delete(long id) {
        jdbcTemplate.update(
                "DELETE FROM transactions WHERE id = ?",
                id);
    }

    public void deleteTaxLines(long transactionId) {
        jdbcTemplate.update(
                "DELETE FROM tax_lines WHERE transaction_id = ?",
                transactionId);
    }

    public void deleteFeeLines(long transactionId) {
        jdbcTemplate.update(
                "DELETE FROM fee_lines WHERE transaction_id = ?",
                transactionId);
    }

    public record TransactionRecord(
            long id,
            String externalId,
            String type,
            String currency,
            String occurredAt,
            BigDecimal total,
            String metadataJson) {
    }
}
