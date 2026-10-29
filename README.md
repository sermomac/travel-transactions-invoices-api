# Travel Transactions and Invoices API

A Spring Boot REST API for managing travel transactions and creating invoices from one or more transactions. Supports flights, hotels, rail journeys, and Navan fees, including tax and fee lines, type-specific metadata, invoice snapshots, and rollups.

## Requirements

- Java 21
- Maven 3.10+
- SQLite

## Run locally

From the project root:

```bash
mvn test
mvn spring-boot:run
```

The API runs at `http://localhost:8080`.

The application uses SQLite at `./data/travel-inventory.db`. The schema is initialized from `src/main/resources/schema.sql`.

## API overview

| Method | Endpoint             | Description                                     |
| ------ | -------------------- | ----------------------------------------------- |
| POST   | `/transactions`      | Create a transaction                            |
| GET    | `/transactions`      | List transactions with pagination               |
| GET    | `/transactions/{id}` | Retrieve a transaction and its tax/fee lines    |
| PATCH  | `/transactions/{id}` | Update a transaction not included in an invoice |
| DELETE | `/transactions/{id}` | Delete a transaction not included in an invoice |
| POST   | `/invoices`          | Create an invoice from transaction IDs          |
| GET    | `/invoices`          | List invoices with pagination                   |
| GET    | `/invoices/{id}`     | Retrieve an invoice, snapshots, and rollups     |
| PATCH  | `/invoices/{id}`     | Replace the transactions in an invoice          |
| DELETE | `/invoices/{id}`     | Delete an invoice and its snapshots             |

List endpoints default to `limit=50` and `offset=0`. The maximum page size is 100.

## Create transactions

All transaction requests use these common fields:

- `external_id`: required unique external identifier
- `type`: `flight`, `hotel`, `rail`, or `navan_fee`
- `currency`: currency code, such as `EUR`
- `occurred_at`: transaction timestamp
- `total`: non-negative amount
- `metadata`: type-specific JSON object
- `tax_lines`: required list of tax lines with `name`, `rate`, and `amount`
- `fee_lines`: required list of fee lines with `name` and `amount`

### Flight

```bash
curl -i -X POST http://localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -d '{
    "external_id": "flight-example-001",
    "type": "flight",
    "currency": "EUR",
    "occurred_at": "2026-03-11T06:40:00Z",
    "total": 220.00,
    "metadata": {
      "origin": "TXL",
      "destination": "LHR",
      "fare": 180,
      "coupons": [
        {
          "flight_number": "BA982",
          "from": "TXL",
          "to": "LHR",
          "carrier": "BA",
          "departure": "2026-03-11T07:00:00Z",
          "arrival": "2026-03-11T08:20:00Z"
        }
      ]
    },
    "tax_lines": [
      {"name": "Airport tax", "amount": 15.00}
    ],
    "fee_lines": [
      {"name": "YQ", "amount": 25.00}
    ]
  }'
```

### Hotel

```bash
curl -i -X POST http://localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -d '{
    "external_id": "hotel-example-001",
    "type": "hotel",
    "currency": "EUR",
    "occurred_at": "2026-03-11T06:40:00Z",
    "total": 331.70,
    "metadata": {
      "name": "Hotel Mitte London",
      "address": {
        "street": "12 Kingsway",
        "city": "London",
        "country": "GB"
      },
      "check_in": "2026-03-11",
      "check_out": "2026-03-13",
      "nights": 2,
      "room_rate_per_night": 140,
      "extras": ["Breakfast"]
    },
    "tax_lines": [
      {"name": "VAT", "rate": 0.07, "amount": 21.70}
    ],
    "fee_lines": []
  }'
```

### Rail

```bash
curl -i -X POST http://localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -d '{
    "external_id": "rail-example-001",
    "type": "rail",
    "currency": "EUR",
    "occurred_at": "2026-03-13T08:00:00Z",
    "total": 94.00,
    "metadata": {
      "legs": [
        {
          "origin": "London St Pancras",
          "destination": "Lille Europe",
          "departure": "2026-03-13T08:00:00Z",
          "arrival": "2026-03-13T10:30:00Z",
          "train_number": "EST9108",
          "class": "standard"
        },
        {
          "origin": "Lille Europe",
          "destination": "Paris Nord",
          "departure": "2026-03-13T11:00:00Z",
          "arrival": "2026-03-13T12:00:00Z",
          "train_number": "EST9122",
          "class": "standard"
        }
      ]
    },
    "tax_lines": [],
    "fee_lines": [
      {"name": "Booking fee", "amount": 4.00}
    ]
  }'
```

### Navan trip fee

```bash
curl -i -X POST http://localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -d '{
    "external_id": "navan-trip-fee-example",
    "type": "navan_fee",
    "currency": "EUR",
    "occurred_at": "2026-03-11T06:40:00Z",
    "total": 25.00,
    "metadata": {
      "fee_kind": "trip_fee",
      "description": "Navan trip fee",
      "related_transaction_external_id": "flight-example-001"
    },
    "tax_lines": [],
    "fee_lines": []
  }'
```

### Navan agent-call fee

```bash
curl -i -X POST http://localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -d '{
    "external_id": "navan-agent-call-example",
    "type": "navan_fee",
    "currency": "EUR",
    "occurred_at": "2026-03-11T06:40:00Z",
    "total": 15.00,
    "metadata": {
      "fee_kind": "agent_call_fee",
      "description": "Agent fee for support call",
      "related_transaction_external_id": "hotel-example-001"
    },
    "tax_lines": [],
    "fee_lines": []
  }'
```

The `navan_fee` type accepts `fee_kind` values `trip_fee` and `agent_call_fee`.

## Retrieve and list transactions

```bash
# Retrieve a transaction
curl -i http://localhost:8080/transactions/1

# List the first page
curl -i 'http://localhost:8080/transactions'

# Request a page
curl -i 'http://localhost:8080/transactions?limit=20&offset=0'

# A requested limit above 100 is capped at 100
curl -i 'http://localhost:8080/transactions?limit=1000&offset=0'
```

## Duplicate transaction handling

`external_id` is unique in the database. A repeated identifier returns HTTP `409 Conflict`.

```bash
curl -i -X POST http://localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -d '{
    "external_id": "flight-example-001",
    "type": "flight",
    "currency": "EUR",
    "occurred_at": "2026-03-11T06:40:00Z",
    "total": 220,
    "metadata": {
      "origin": "TXL",
      "destination": "LHR",
      "fare": 180,
      "coupons": [
        {
          "flight_number": "BA982",
          "from": "TXL",
          "to": "LHR",
          "carrier": "BA",
          "departure": "2026-03-11T07:00:00Z",
          "arrival": "2026-03-11T08:20:00Z"
        }
      ]
    },
    "tax_lines": [],
    "fee_lines": []
  }'
```

## Update and delete transactions

Transactions included in an invoice cannot be updated or deleted; these operations return HTTP `409 Conflict`.

The transaction update endpoint requires the full set of fields used for transaction creation, including valid type-specific metadata.

Update a transaction that is **not included in an invoice**:

```bash
curl -i -X PATCH http://localhost:8080/transactions/1 \
  -H 'Content-Type: application/json' \
  -d '{
    "type": "flight",
    "currency": "EUR",
    "occurred_at": "2026-03-11T06:40:00Z",
    "total": 225.00,
    "metadata": {
      "origin": "TXL",
      "destination": "LHR",
      "fare": 185,
      "coupons": [
        {
          "flight_number": "BA982",
          "from": "TXL",
          "to": "LHR",
          "carrier": "BA",
          "departure": "2026-03-11T07:00:00Z",
          "arrival": "2026-03-11T08:20:00Z"
        }
      ]
    },
    "tax_lines": [],
    "fee_lines": []
  }'
```

Delete a transaction that is **not included in an invoice**:

```bash
curl -i -X DELETE http://localhost:8080/transactions/1
```

## Create and retrieve invoices

Create an invoice from one transaction:

```bash
curl -i -X POST http://localhost:8080/invoices \
  -H 'Content-Type: application/json' \
  -d '{"transaction_id": 1}'
```

Create an invoice from multiple transactions:

```bash
curl -i -X POST http://localhost:8080/invoices \
  -H 'Content-Type: application/json' \
  -d '{"transaction_ids": [1, 2, 3]}'
```

All transactions in an invoice must use the same currency. Mixed-currency requests return HTTP `409 Conflict`. Duplicate transaction IDs are rejected.

```bash
# Retrieve an invoice and its snapshots/rollups
curl -i http://localhost:8080/invoices/1

# List invoices
curl -i 'http://localhost:8080/invoices?limit=50&offset=0'
```

Invoice responses include transaction snapshots, tax rollups, and fee rollups. Snapshots preserve the transaction data used when the invoice was created.

## Replace an invoice's transactions

Replace an invoice's transaction set with a new one:

```bash
curl -i -X PATCH http://localhost:8080/invoices/1 \
  -H 'Content-Type: application/json' \
  -d '{"transaction_ids": [2, 3]}'
```

The replacement must contain valid, existing transaction IDs with a consistent currency. Failed validation leaves the existing invoice unchanged.

## Delete an invoice

```bash
curl -i -X DELETE http://localhost:8080/invoices/1
```

Deleting an invoice removes the invoice and its snapshots. The underlying transactions remain.

## Error behavior

| HTTP status       | Meaning                                                                       |
| ----------------- | ----------------------------------------------------------------------------- |
| `201 Created`     | Transaction or invoice created                                                |
| `200 OK`          | Retrieval, listing, or successful replacement                                 |
| `204 No Content`  | Invoice or transaction deleted                                                |
| `400 Bad Request` | Invalid input or metadata                                                     |
| `404 Not Found`   | Requested resource does not exist                                             |
| `409 Conflict`    | Duplicate external ID, mixed currency, or transaction protected by an invoice |

## Tests

Run the automated test suite:

```bash
mvn test
```

Tests cover duplicate external IDs, all five transaction types, invoice snapshots and totals, mixed-currency rejection, invoiced transaction restrictions, concurrent duplicate creation, pagination limits, and missing-resource handling.
