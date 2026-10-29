# Architecture — Travel Transactions and Invoices API

## Overview

The application is a Java 21 Spring Boot REST API backed by SQLite. It separates HTTP handling, business rules, and persistence while keeping the application model small.

### Application layers

- **Controllers:** `TransactionController` and `InvoiceController` expose REST endpoints, map request/response data, and translate expected validation and conflict errors into HTTP statuses.
- **Services:** `TransactionService` and `InvoiceService` implement validation, business rules, transaction boundaries, invoice creation/replacement, and rollups.
- **Repositories:** `TransactionRepository` and `InvoiceRepository` use Spring JDBC to execute SQL queries and mutations.
- **Line model:** `TransactionLine` defines the tax and fee line record types.

## Transaction identity and duplicate handling

Every transaction has a required `external_id`, protected by a database `UNIQUE` constraint. The service checks for an existing identifier before insertion for a clear error response, while the database constraint remains the authoritative safeguard against concurrent duplicate requests.

A duplicate identifier returns HTTP `409 Conflict`. Transaction creation is wrapped in a database transaction so that failure while inserting a tax or fee line rolls back the transaction header and any lines already written.

## Transaction types and metadata

Transactions support four types: `flight`, `hotel`, `rail`, and `navan_fee`.

- **Flight:** origin, destination, fare, and coupon details.
- **Hotel:** property name, address, check-in/check-out dates, nights, and room-rate information.
- **Rail:** one or more journey legs, including origins, destinations, and travel times.
- **Navan fee:** `fee_kind` identifies either `trip_fee` or `agent_call_fee`.

The metadata is stored as JSON in `transactions.metadata_json`. The service validates required metadata fields for the selected type.

## Tax and fee lines

Tax and fee lines are stored separately from transaction headers:

- `tax_lines` stores a tax name, optional rate, amount, and transaction reference.
- `fee_lines` stores a fee name, amount, and transaction reference.

This structure allows transactions to have zero or more tax and fee lines without adding type-specific columns to the transaction table. Indexes on each line table's `transaction_id` support retrieval.

## Invoice snapshots

An invoice records a currency and total, with its included transactions represented in `invoice_transaction_snapshots`. The snapshot table copies the relevant transaction fields, including `external_id`, type, currency, occurrence time, total, and metadata JSON.

Tax and fee details are copied into `invoice_tax_snapshots` and `invoice_fee_snapshots`. Invoice responses calculate tax and fee rollups from these snapshot tables rather than from mutable transaction data.

This preserves invoice history if transaction data changes elsewhere. To maintain consistency, the API rejects updates and deletes of transactions that belong to an invoice. Invoice replacement validates the proposed transaction set before replacing snapshots and is transactional. Deleting an invoice removes its snapshots but preserves the underlying transactions.

## Currency and pagination

All transactions included in an invoice must use the same currency. Mixed-currency invoice creation or replacement is rejected with HTTP `409 Conflict`. Duplicate transaction IDs in a request are rejected.

Transaction and invoice list endpoints default to 50 results and cap the requested page size at 100. Pagination uses SQL `LIMIT` and `OFFSET`, with repository queries retrieving only the requested page.

## High-volume considerations

The schema includes indexes for transaction tax/fee lookups and invoice snapshot lookups. The unique `external_id` constraint supports indexed duplicate detection. At higher volumes, additional considerations include query-plan review, connection-pool tuning, retention and archival strategy, and load testing under concurrent writes.

SQLite is appropriate for this take-home implementation and a single-node workload. For a production deployment requiring sustained concurrent writes or horizontal scaling, a client/server relational database such as PostgreSQL would be worth evaluating.
