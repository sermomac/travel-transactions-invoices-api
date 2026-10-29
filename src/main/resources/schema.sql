PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS transactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    external_id TEXT NOT NULL UNIQUE,
    type TEXT NOT NULL CHECK (type IN ('flight', 'hotel', 'rail', 'navan_fee')),
    currency TEXT NOT NULL,
    occurred_at TEXT NOT NULL,
    total NUMERIC NOT NULL,
    metadata_json TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tax_lines (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    transaction_id INTEGER NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    rate NUMERIC,
    amount NUMERIC NOT NULL
);

CREATE TABLE IF NOT EXISTS fee_lines (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    transaction_id INTEGER NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    amount NUMERIC NOT NULL
);

CREATE TABLE IF NOT EXISTS invoices (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    currency TEXT NOT NULL,
    total NUMERIC NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS invoice_transaction_snapshots (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    invoice_id INTEGER NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    transaction_id INTEGER NOT NULL,
    external_id TEXT NOT NULL,
    type TEXT NOT NULL,
    currency TEXT NOT NULL,
    occurred_at TEXT NOT NULL,
    total NUMERIC NOT NULL,
    metadata_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS invoice_tax_snapshots (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    invoice_line_id INTEGER NOT NULL REFERENCES invoice_transaction_snapshots(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    rate NUMERIC,
    amount NUMERIC NOT NULL
);

CREATE TABLE IF NOT EXISTS invoice_fee_snapshots (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    invoice_line_id INTEGER NOT NULL REFERENCES invoice_transaction_snapshots(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    amount NUMERIC NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tax_transaction
    ON tax_lines(transaction_id);

CREATE INDEX IF NOT EXISTS idx_fee_transaction
    ON fee_lines(transaction_id);

CREATE INDEX IF NOT EXISTS idx_invoice_snapshot_transaction
    ON invoice_transaction_snapshots(transaction_id);

CREATE INDEX IF NOT EXISTS idx_invoice_snapshot_invoice
    ON invoice_transaction_snapshots(invoice_id);

CREATE UNIQUE INDEX IF NOT EXISTS
    uq_invoice_snapshot_invoice_transaction
ON invoice_transaction_snapshots(invoice_id, transaction_id);
