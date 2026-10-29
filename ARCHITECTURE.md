# Architecture

The application is a Java 21 Spring Boot REST API using SQLite and Spring JDBC, organized into controllers (`TransactionController`, `InvoiceController`), services (`TransactionService`, `InvoiceService`), and repositories (`TransactionRepository`, `InvoiceRepository`).

## Transaction identity and concurrent POSTs

`transactions.external_id` is `NOT NULL` and protected by a database `UNIQUE` constraint. The service checks for an existing ID for a clear conflict response, but the database constraint is the authoritative protection when concurrent POST requests race; duplicates return `409 Conflict`. Transaction creation and insertion of its tax/fee lines are atomic, so a failed child insert rolls back the whole create.

## Tables and models

- `transactions` stores common fields, including `external_id`, `type`, `currency`, `occurred_at`, `total`, and `metadata_json`.
- `tax_lines` stores a transaction reference, tax name, optional rate, and amount. `fee_lines` stores a transaction reference, fee name, and amount. Separate tables preserve tax-specific data and allow tax and fee totals to be calculated independently.
- `invoices` stores invoice currency and total.
- `invoice_transaction_snapshots` copies the transaction fields and metadata used for an invoice. `invoice_tax_snapshots` and `invoice_fee_snapshots` copy its tax and fee lines.

Type-specific data is stored in `transactions.metadata_json` and validated for the selected type:

- **`flight`:** overall origin/destination (OD), with coupons containing flight numbers and related flight details.
- **`hotel`:** property name and address.
- **`rail`:** a collection of journey legs, each with its origin and destination.
- **`navan_fee`:** `fee_kind` distinguishes `trip_fee` from `agent_call_fee`.

## Invoice snapshots

Invoice details and tax/fee rollups are calculated from the saved snapshot tables rather than live transaction data, preserving the invoice's original amounts and metadata. Transactions included in an invoice cannot be patched or deleted (`409 Conflict`). Invoice creation/replacement is atomic, and transactions in a single invoice must use the same currency.

## High volume

`GET /transactions` and `GET /invoices` use SQL `LIMIT`/`OFFSET` pagination (default `limit=50`, `offset=0`; maximum limit `100`) so the API does not load an unbounded list into memory.

Important indexes in the schema are the unique constraint/index on `transactions.external_id`, `idx_tax_transaction` on `tax_lines(transaction_id)`, `idx_fee_transaction` on `fee_lines(transaction_id)`, `idx_invoice_snapshot_transaction` on `invoice_transaction_snapshots(transaction_id)`, `idx_invoice_snapshot_invoice` on `invoice_transaction_snapshots(invoice_id)`, and the unique `uq_invoice_snapshot_invoice_transaction` on `invoice_transaction_snapshots(invoice_id, transaction_id)`. Primary-key indexes support ID lookups.

Under load, SQLite write-lock contention is the most likely early bottleneck for concurrent writes; large `OFFSET` values can also slow deep-page requests. The uniqueness constraint protects against duplicate IDs, transactions keep multi-row writes atomic, and bounded SQL pagination limits response size.
