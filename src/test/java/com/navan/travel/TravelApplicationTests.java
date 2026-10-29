package com.navan.travel;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest
@AutoConfigureMockMvc
class TravelApplicationTests {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

@Test
void allFiveFixtureTransactionTypesRoundTrip() throws Exception {
    JsonNode fixtures = objectMapper.readTree(
            Files.readString(Path.of("fixtures/task-b/seed-payloads.json"))
    );

    String[] fixtureNames = {
        "flight", "hotel", "rail", "trip_fee", "agent_call_fee"
    };

    for (String fixtureName : fixtureNames) {
        JsonNode fixture = fixtures.get(fixtureName);
        ObjectNode payload = (ObjectNode) objectMapper.readTree(fixture.toString());
        String externalId = fixture.get("external_id").asText()
                + "-roundtrip-" + System.nanoTime();
        payload.put("external_id", externalId);

        String response = mockMvc.perform(post("/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload.toString()))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode created = objectMapper.readTree(response);
        long id = created.get("id").asLong();

        mockMvc.perform(get("/transactions/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.external_id").value(externalId))
                .andExpect(jsonPath("$.type").value(fixture.get("type").asText()))
                .andExpect(jsonPath("$.metadata").isMap())
                .andExpect(jsonPath("$.metadata").isNotEmpty());

        switch (fixtureName) {
            case "flight" -> mockMvc.perform(get("/transactions/" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.metadata.origin").value("TXL"))
                    .andExpect(jsonPath("$.metadata.destination").value("LHR"))
                    .andExpect(jsonPath("$.metadata.coupons[0].flight_number").value("BA982"));

            case "hotel" -> mockMvc.perform(get("/transactions/" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.metadata.name").value("Hotel Mitte London"))
                    .andExpect(jsonPath("$.metadata.address.city").value("London"))
                    .andExpect(jsonPath("$.metadata.address.country").value("GB"));

            case "rail" -> mockMvc.perform(get("/transactions/" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.metadata.legs").isArray())
                    .andExpect(jsonPath("$.metadata.legs.length()").value(2))
                    .andExpect(jsonPath("$.metadata.legs[0].train_number").value("EST9108"));

            case "trip_fee" -> mockMvc.perform(get("/transactions/" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.metadata.fee_kind").value("trip_fee"));

            case "agent_call_fee" -> mockMvc.perform(get("/transactions/" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.metadata.fee_kind").value("agent_call_fee"));

            default -> throw new AssertionError("Unexpected fixture: " + fixtureName);
        }
    }
}

@Test
void duplicateExternalIdDoesNotCreateSecondTransaction() throws Exception {
    JsonNode fixtures = objectMapper.readTree(
            Files.readString(Path.of("fixtures/task-b/seed-payloads.json"))
    );

    ObjectNode payload = (ObjectNode) objectMapper.readTree(
            fixtures.get("flight").toString()
    );

    String externalId = "duplicate-test-" + System.nanoTime();
    payload.put("external_id", externalId);

    String firstResponse = mockMvc.perform(post("/transactions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload.toString()))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    long originalId = objectMapper.readTree(firstResponse)
            .get("id")
            .asLong();

    long rowsBeforeDuplicate = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM transactions WHERE external_id = ?",
            Long.class,
            externalId
    );

    var secondResult = mockMvc.perform(post("/transactions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload.toString()))
            .andReturn();

    int secondStatus = secondResult.getResponse().getStatus();

    assertTrue(
            secondStatus == 409 || secondStatus == 200,
            "Expected duplicate POST to return 409 or 200, but got " + secondStatus
    );

    if (secondStatus == 200) {
        JsonNode secondResponse = objectMapper.readTree(
                secondResult.getResponse().getContentAsString()
        );
        assertEquals(originalId, secondResponse.get("id").asLong());
    }

    long rowsAfterDuplicate = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM transactions WHERE external_id = ?",
            Long.class,
            externalId
    );

    assertEquals(rowsBeforeDuplicate, rowsAfterDuplicate,
            "Duplicate external_id must not create another transaction row");
}
    
@Test
void invoiceFromAllFiveFixturesTotals68570() throws Exception {
    JsonNode fixtures = objectMapper.readTree(
            Files.readString(Path.of("fixtures/task-b/seed-payloads.json"))
    );

    JsonNode expected = objectMapper.readTree(
            Files.readString(Path.of("fixtures/task-b/expected-invoice.json"))
    );

    String[] fixtureNames = {
            "flight", "hotel", "rail", "trip_fee", "agent_call_fee"
    };

    List<Long> transactionIds = new java.util.ArrayList<>();

    for (String fixtureName : fixtureNames) {
        JsonNode fixture = fixtures.get(fixtureName);
        ObjectNode payload = (ObjectNode) objectMapper.readTree(
                fixture.toString()
        );

        payload.put(
                "external_id",
                fixture.get("external_id").asText()
                        + "-invoice-" + System.nanoTime()
        );

        String response = mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        transactionIds.add(
                objectMapper.readTree(response).get("id").asLong()
        );
    }

    String invoiceRequest = objectMapper.createObjectNode()
            .set("transaction_ids", objectMapper.valueToTree(transactionIds))
            .toString();

    String invoiceResponse = mockMvc.perform(post("/invoices")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(invoiceRequest))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    long invoiceId = objectMapper.readTree(invoiceResponse)
            .get("id")
            .asLong();

    String invoiceJson = mockMvc.perform(get("/invoices/{id}", invoiceId))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();

JsonNode invoice = objectMapper.readTree(invoiceJson);
JsonNode expectedTotals = expected.get("totals");
JsonNode transactions = invoice.get("transactions");

assertEquals(
        expected.get("currency").asText(),
        invoice.get("currency").asText()
);
assertEquals(
        expectedTotals.get("grand_total").asDouble(),
        invoice.get("total").asDouble(),
        0.001
);
assertEquals(
        expected.get("line_count_minimum").asInt(),
        transactions.size()
);

java.util.Map<String, Double> actualTotals = new java.util.HashMap<>();

for (JsonNode transaction : transactions) {
    String type = transaction.get("type").asText();
    String key = type.equals("navan_fee")
            ? transaction.get("metadata").get("fee_kind").asText()
            : type;

    actualTotals.put(key, transaction.get("total").asDouble());
}

for (String key : fixtureNames) {
    String expectedKey = key.equals("trip_fee") || key.equals("agent_call_fee")
            ? key
            : key;

    assertTrue(
            actualTotals.containsKey(expectedKey),
            "Missing invoice contribution: " + expectedKey
    );
    assertEquals(
            expectedTotals.get(expectedKey).asDouble(),
            actualTotals.get(expectedKey),
            0.001,
            "Incorrect invoice contribution: " + expectedKey
    );
}
}
    
@Test
void invoiceSnapshotRemainsUnchangedWhenStoredTransactionTotalChanges()
        throws Exception {

    long transactionId = transactionService.createTransaction(
            "snapshot-frozen-test-" + System.nanoTime(),
            "flight",
            "EUR",
            "2026-03-11T06:40:00Z",
            new BigDecimal("220.00"),
            """
            {
              "origin": "TXL",
              "destination": "LHR",
              "fare": 180.0,
              "coupons": [
                {
                  "carrier": "BA",
                  "flight_number": "BA982",
                  "from": "TXL",
                  "to": "LHR",
                  "departure": "2026-03-11T07:00:00Z",
                  "arrival": "2026-03-11T08:20:00Z"
                }
              ]
            }
            """,
            List.of(
                    new TransactionLine.TaxLine(
                            "Airport tax", null, new BigDecimal("15.00"))
            ),
            List.of(
                    new TransactionLine.FeeLine(
                            "YQ", new BigDecimal("25.00"))
            )
    );

    long invoiceId = invoiceService.createInvoice(List.of(transactionId));

    // Change the underlying transaction after the invoice snapshot exists.
    jdbcTemplate.update(
            "UPDATE transactions SET total = ? WHERE id = ?",
            new BigDecimal("999.00"),
            transactionId
    );

    assertEquals(
        0,
        new BigDecimal("999.00").compareTo(
                transactionRepository.findById(transactionId)
                        .orElseThrow()
                        .total()
        ),
        "Stored transaction total should have changed to 999.00"
   );

    String invoiceJson = mockMvc.perform(get("/invoices/{id}", invoiceId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode invoice = objectMapper.readTree(invoiceJson);

    assertEquals(
        0,
        new BigDecimal("220.00").compareTo(
                invoice.get("total").decimalValue()
        ),
        "Invoice total should remain unchanged"
);

    JsonNode flightLine = null;
    for (JsonNode transaction : invoice.get("transactions")) {
        if ("flight".equals(transaction.get("type").asText())) {
            flightLine = transaction;
            break;
        }
    }

    assertTrue(flightLine != null, "Invoice should contain the flight line");

    assertEquals(
        0,
        new BigDecimal("220.00").compareTo(
                flightLine.get("total").decimalValue()
        ),
        "Flight line total should remain unchanged"
);
}

@Test
void mixedCurrencyInvoiceIsRejectedWithoutCreatingInvoice() throws Exception {
    long eurTransactionId = transactionService.createTransaction(
            "mixed-currency-eur-test",
            "flight",
            "EUR",
            "2026-03-11T06:40:00Z",
            new BigDecimal("220.00"),
            """
            {
              "origin": "TXL",
              "destination": "LHR",
              "fare": 180,
              "coupons": [
                {
                  "flight_number": "BA982",
                  "from": "TXL",
                  "to": "LHR",
                  "departure": "2026-03-11T06:40:00Z",
                  "arrival": "2026-03-11T08:10:00Z"
                }
              ]
            }
            """,
            List.of(),
            List.of()
    );

    long usdTransactionId = transactionService.createTransaction(
            "mixed-currency-usd-test",
            "hotel",
            "USD",
            "2026-03-11T06:40:00Z",
            new BigDecimal("100.00"),
            """
            {
              "name": "Test Hotel",
              "address": {
                "street": "1 Test Street",
                "city": "London",
                "country": "GB"
              },
              "check_in": "2026-03-11",
              "check_out": "2026-03-12",
              "nights": 1,
              "room_rate_per_night": 100
            }
            """,
            List.of(),
            List.of()
    );

    long invoiceCountBefore = invoiceService.count();

    mockMvc.perform(post("/invoices")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(
                            java.util.Map.of(
                                    "transaction_ids",
                                    List.of(eurTransactionId, usdTransactionId)
                            )
                    )))
            .andExpect(status().isConflict());

    assertEquals(invoiceCountBefore, invoiceService.count());
}

@Test
void concurrentDuplicateExternalIdCreatesOnlyOneTransaction() throws Exception {
        String externalId = "concurrent-duplicate-" + System.nanoTime();

        String payload = """
                {
                "external_id": "%s",
                "type": "flight",
                "currency": "EUR",
                "occurred_at": "2026-03-11T06:40:00Z",
                "total": 220.00,
                "tax_lines": [],
                "fee_lines": [],
                "metadata": {
                        "origin": "TXL",
                        "destination": "LHR",
                        "fare": 180,
                        "coupons": [
                        {
                        "flight_number": "BA982",
                        "from": "TXL",
                        "to": "LHR",
                        "departure": "2026-03-11T06:40:00Z",
                        "arrival": "2026-03-11T08:10:00Z"
                        }
                        ]
                }
                }
                """.formatted(externalId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
                java.util.concurrent.Callable<Integer> request = () -> {
                ready.countDown();

                if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start");
                }

                return mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                        .andReturn()
                        .getResponse()
                        .getStatus();
                };

                Future<Integer> first = executor.submit(request);
                Future<Integer> second = executor.submit(request);

                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();

                int firstStatus = first.get(15, TimeUnit.SECONDS);
                int secondStatus = second.get(15, TimeUnit.SECONDS);

                assertTrue(
                        firstStatus == 201 || firstStatus == 409,
                        "Unexpected first response status: " + firstStatus
                );
                assertTrue(
                        secondStatus == 201 || secondStatus == 409,
                        "Unexpected second response status: " + secondStatus
                );

                Integer rowCount = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM transactions WHERE external_id = ?",
                        Integer.class,
                        externalId
                );

                assertEquals(1, rowCount);
        } finally {
                executor.shutdownNow();
        }
        }


@Test
void invoicedTransactionCannotBeUpdatedOrDeleted() throws Exception {
    long transactionId = transactionService.createTransaction(
            "invoice-protection-test-" + System.nanoTime(),
            "flight",
            "EUR",
            "2026-03-11T06:40:00Z",
            new BigDecimal("220.00"),
            """
            {
              "origin": "TXL",
              "destination": "LHR",
              "fare": 180,
              "coupons": [
                {
                  "flight_number": "BA982",
                  "from": "TXL",
                  "to": "LHR",
                  "departure": "2026-03-11T06:40:00Z",
                  "arrival": "2026-03-11T08:10:00Z"
                }
              ]
            }
            """,
            List.of(),
            List.of()
    );

    long invoiceId = invoiceService.createInvoice(List.of(transactionId));

    String patchPayload = """
            {
              "type": "flight",
              "currency": "EUR",
              "occurred_at": "2026-03-11T06:40:00Z",
              "total": 999.00,
              "tax_lines": [],
              "fee_lines": [],
              "metadata": {
                "origin": "TXL",
                "destination": "LHR",
                "fare": 180,
                "coupons": [
                  {
                    "flight_number": "BA982",
                    "from": "TXL",
                    "to": "LHR",
                    "departure": "2026-03-11T06:40:00Z",
                    "arrival": "2026-03-11T08:10:00Z"
                  }
                ]
              }
            }
            """;

    mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .patch("/transactions/{id}", transactionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(patchPayload)
    ).andExpect(status().isConflict());

    assertEquals(
            0,
            new BigDecimal("220.00").compareTo(
                    transactionRepository.findById(transactionId)
                            .orElseThrow()
                            .total()
            ),
            "Stored transaction total should remain unchanged"
    );

    String invoiceJson = mockMvc.perform(get("/invoices/{id}", invoiceId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode invoice = objectMapper.readTree(invoiceJson);

    assertEquals(
            0,
            new BigDecimal("220.00").compareTo(invoice.get("total").decimalValue()),
            "Invoice total should remain unchanged"
    );

    JsonNode flightLine = null;
    for (JsonNode transaction : invoice.get("transactions")) {
        if ("flight".equals(transaction.get("type").asText())) {
            flightLine = transaction;
            break;
        }
    }

    assertTrue(flightLine != null, "Invoice should contain the flight line");

    assertEquals(
            0,
            new BigDecimal("220.00").compareTo(flightLine.get("total").decimalValue()),
            "Invoice flight line total should remain unchanged"
    );

    mockMvc.perform(
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/transactions/{id}", transactionId)
)
.andExpect(status().isConflict());

assertTrue(
        transactionRepository.findById(transactionId).isPresent(),
        "Transaction should still exist after rejected DELETE"
);

    assertThrows(
            IllegalStateException.class,
            () -> transactionService.deleteTransaction(transactionId)
    );
}

@Test
void deletingInvoicePreservesUnderlyingTransactions() throws Exception {
    long transactionId = transactionService.createTransaction(
            "invoice-delete-test-" + System.nanoTime(),
            "flight",
            "EUR",
            "2026-03-11T06:40:00Z",
            new BigDecimal("220.00"),
            """
            {
              "origin": "TXL",
              "destination": "LHR",
              "fare": 180,
              "coupons": [
                {
                  "flight_number": "BA982",
                  "from": "TXL",
                  "to": "LHR",
                  "departure": "2026-03-11T06:40:00Z",
                  "arrival": "2026-03-11T08:10:00Z"
                }
              ]
            }
            """,
            List.of(),
            List.of()
    );

    long invoiceId = invoiceService.createInvoice(List.of(transactionId));

    mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .delete("/invoices/{id}", invoiceId)
    ).andExpect(status().isNoContent());

    mockMvc.perform(get("/invoices/{id}", invoiceId))
            .andExpect(status().isNotFound());

    mockMvc.perform(get("/transactions/{id}", transactionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(transactionId));
}

@Test
void transactionPaginationDefaultsAndCapsLimit() throws Exception {
    mockMvc.perform(get("/transactions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.limit").value(50))
            .andExpect(jsonPath("$.offset").value(0));

    mockMvc.perform(get("/transactions")
                    .param("limit", "1000")
                    .param("offset", "0"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.limit").value(100));
        }

@Test
void invoicePaginationDefaultsAndCapsLimit() throws Exception {
    mockMvc.perform(get("/invoices"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.limit").value(50))
            .andExpect(jsonPath("$.offset").value(0));

    mockMvc.perform(get("/invoices")
                    .param("limit", "1000")
                    .param("offset", "0"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.limit").value(100));
}

}