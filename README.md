# Travel Transactions and Invoices API

A Spring Boot REST API for managing travel transactions and creating invoices from one or more transactions. Supported transaction types are flights, hotels, rail journeys, and Navan fees.

## Requirements

- Java 21
- Maven 3.10+
- SQLite

## Run the API

From the project root, run:

```bash
mvn spring-boot:run
```

The API runs at `http://localhost:8080`.

The SQLite database is configured at `./data/travel-inventory.db`. The schema is initialized from `src/main/resources/schema.sql`.

## Run tests

From the project root, run:

```bash
mvn test
```

## API overview

| Method | Endpoint             | Description                                  |
| ------ | -------------------- | -------------------------------------------- |
| POST   | `/transactions`      | Create a transaction                         |
| GET    | `/transactions`      | List transactions                            |
| GET    | `/transactions/{id}` | Retrieve a transaction and its tax/fee lines |
| PATCH  | `/transactions/{id}` | Update a transaction                         |
| DELETE | `/transactions/{id}` | Delete a transaction                         |
| POST   | `/invoices`          | Create an invoice from transaction IDs       |
| GET    | `/invoices`          | List invoices                                |
| GET    | `/invoices/{id}`     | Retrieve an invoice and its snapshots        |
| PATCH  | `/invoices/{id}`     | Replace the transactions in an invoice       |
| DELETE | `/invoices/{id}`     | Delete an invoice                            |

Both list endpoints default to `limit=50` and `offset=0`. The maximum page size is 100.

## Create transactions

Each create request includes `external_id`, `type`, `currency`, `occurred_at`, `total`, `metadata`, `tax_lines`, and `fee_lines`.

The examples use distinct external IDs. Use the database ID returned by each successful create request for later commands.

### 1. Create a flight

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
      "coupons": [{
        "flight_number": "BA982",
        "from": "TXL",
        "to": "LHR",
        "carrier": "BA",
        "departure": "2026-03-11T07:00:00Z",
        "arrival": "2026-03-11T08:20:00Z"
      }]
    },
    "tax_lines": [
      {"name": "Airport tax", "amount": 15.00}
    ],
    "fee_lines": [
      {"name": "YQ", "amount": 25.00}
    ]
  }'
```

### 2. Create a hotel

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

### 3. Create a rail transaction

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

### 4. Create a Navan trip fee

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

### 5. Create a Navan agent-call fee

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

The `navan_fee` type uses `fee_kind` values `trip_fee` and `agent_call_fee`.

## Duplicate create

`external_id` is unique. Repeating a create request with an existing external ID returns `409 Conflict` rather than creating a second transaction.

For example, run the flight creation request above a second time. The duplicate request uses the same `external_id`:

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
      "coupons": []
    },
    "tax_lines": [],
    "fee_lines": []
  }'
```

Expected result for the duplicate request: `409 Conflict`.

## Get and list transactions

Retrieve a transaction by its database ID:

```bash
curl -i http://localhost:8080/transactions/1
```

List transactions:

```bash
curl -i 'http://localhost:8080/transactions'
```

List a specific page:

```bash
curl -i 'http://localhost:8080/transactions?limit=20&offset=0'
```

Request a limit above the maximum:

```bash
curl -i 'http://localhost:8080/transactions?limit=1000&offset=0'
```

The maximum page size is 100.

## Patch and delete transactions

### Patch a transaction not included in an invoice

Replace `1` with the database ID of a transaction that is not included in an invoice.

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
      "coupons": [{
        "flight_number": "BA982",
        "from": "TXL",
        "to": "LHR",
        "carrier": "BA",
        "departure": "2026-03-11T07:00:00Z",
        "arrival": "2026-03-11T08:20:00Z"
      }]
    },
    "tax_lines": [],
    "fee_lines": []
  }'
```

### Delete a transaction not included in an invoice

```bash
curl -i -X DELETE http://localhost:8080/transactions/1
```

### Verify `409 Conflict` for an invoiced transaction

Transactions included in an invoice cannot be patched or deleted. Both operations return `409 Conflict`.

Replace `FLIGHT_TRANSACTION_ID` with the actual database ID of a transaction included in an invoice.

Attempt to patch it:

```bash
curl -i -X PATCH http://localhost:8080/transactions/FLIGHT_TRANSACTION_ID \
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
      "coupons": []
    },
    "tax_lines": [],
    "fee_lines": []
  }'
```

Attempt to delete it:

```bash
curl -i -X DELETE http://localhost:8080/transactions/FLIGHT_TRANSACTION_ID
```

Expected result for both operations: `409 Conflict`. The invoiced transaction remains unchanged.

## Create, get, and list invoices

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
  -d '{"transaction_ids": [1, 2, 3, 4, 5]}'
```

Replace the example IDs with valid transaction database IDs. All transactions in an invoice must use the same currency. A mixed-currency invoice request returns `409 Conflict`.

Retrieve an invoice by ID:

```bash
curl -i http://localhost:8080/invoices/1
```

List invoices:

```bash
curl -i 'http://localhost:8080/invoices'
```

List a specific page:

```bash
curl -i 'http://localhost:8080/invoices?limit=20&offset=0'
```

Request a limit above the maximum:

```bash
curl -i 'http://localhost:8080/invoices?limit=1000&offset=0'
```

The maximum page size is 100.

## Patch and delete invoices

Replace the transactions in an invoice:

```bash
curl -i -X PATCH http://localhost:8080/invoices/1 \
  -H 'Content-Type: application/json' \
  -d '{"transaction_ids": [2, 3]}'
```

Replace the example invoice and transaction IDs with valid database IDs. The replacement transaction set must use a consistent currency.

Delete an invoice:

```bash
curl -i -X DELETE http://localhost:8080/invoices/1
```

Deleting an invoice removes the invoice and its snapshots. The underlying transactions remain available.

## Error behavior

| HTTP status       | Meaning                                                                              |
| ----------------- | ------------------------------------------------------------------------------------ |
| `201 Created`     | Transaction or invoice created                                                       |
| `200 OK`          | Successful retrieval, listing, or replacement                                        |
| `204 No Content`  | Successful deletion                                                                  |
| `400 Bad Request` | Invalid request, metadata, or pagination parameters                                  |
| `404 Not Found`   | Requested resource or referenced ID does not exist                                   |
| `409 Conflict`    | Duplicate `external_id`, mixed currency, or update/delete of an invoiced transaction |
