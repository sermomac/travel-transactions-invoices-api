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
import org.springframework.dao.DuplicateKeyException;
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
    void duplicateExternalIdDoesNotCreateSecondTransaction() {
        String externalId = "duplicate-test-001";
        long countBefore = transactionRepository.count();

        String metadata = """
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
                """;

        transactionService.createTransaction(
                externalId,
                "flight",
                "EUR",
                "2026-03-11T06:40:00Z",
                new BigDecimal("220.00"),
                metadata,
                List.of(),
                List.of()
        );

        assertThrows(DuplicateKeyException.class, () ->
                transactionService.createTransaction(
                        externalId,
                        "flight",
                        "EUR",
                        "2026-03-11T06:40:00Z",
                        new BigDecimal("220.00"),
                        metadata,
                        List.of(),
                        List.of()
                )
        );

        assertEquals(countBefore + 1, transactionRepository.count());
    }

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

        mockMvc.perform(post("/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload.toString()))
                .andExpect(status().isCreated());

        mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/transactions")
                        .param("limit", "100")
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.transactions[?(@.external_id == '" + externalId + "')]"
                ).isNotEmpty());
    }
   }

    @Test
    void deletingInvoicePreservesUnderlyingTransactions() {
        long transactionId = transactionService.createTransaction(
                "invoice-delete-test-001",
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

        invoiceService.deleteInvoice(invoiceId);

        assertEquals(
                true,
                transactionRepository.findById(transactionId).isPresent()
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> invoiceService.findById(invoiceId)
        );
    }

    @Test
    void invoiceSnapshotRemainsUnchangedWhenStoredTransactionTotalChanges() {
    long transactionId = transactionService.createTransaction(
            "snapshot-frozen-test-001",
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

    // Simulate a stored transaction changing after the invoice was created.
    jdbcTemplate.update(
            "UPDATE transactions SET total = ? WHERE id = ?",
            new BigDecimal("999.00"),
            transactionId
    );

    assertEquals(
            new BigDecimal("999"),
            transactionRepository.findById(transactionId)
                    .orElseThrow()
                    .total()
    );

    assertEquals(
            new BigDecimal("220"),
            invoiceService.findTransactionSnapshots(invoiceId)
                    .get(0)
                    .total()
    );

    assertEquals(
            new BigDecimal("220"),
            invoiceService.findById(invoiceId).total()
    );
    }

    @Test
    void invoicedTransactionCannotBeUpdatedOrDeleted() {
    long transactionId = transactionService.createTransaction(
            "invoice-protection-test-001",
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

    assertThrows(IllegalStateException.class, () ->
            transactionService.updateTransaction(
                    transactionId,
                    "flight",
                    "EUR",
                    "2026-03-11T06:40:00Z",
                    new BigDecimal("999.00"),
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
            )
    );

    assertThrows(
            IllegalStateException.class,
            () -> transactionService.deleteTransaction(transactionId)
    );

    assertEquals(
            new BigDecimal("220"),
            transactionRepository.findById(transactionId)
                    .orElseThrow()
                    .total()
    );

    assertEquals(
            new BigDecimal("220"),
            invoiceService.findById(invoiceId).total()
    );
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
    void mixedCurrencyInvoiceIsRejectedWithoutCreatingInvoice() {
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

    assertThrows(
            IllegalStateException.class,
            () -> invoiceService.createInvoice(
                    List.of(eurTransactionId, usdTransactionId))
    );

    assertEquals(invoiceCountBefore, invoiceService.count());
    }

    @Test
    void failedMixedCurrencyReplacementPreservesOriginalInvoice() {
    long eurTransactionId = transactionService.createTransaction(
            "replace-rollback-eur-test",
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
            "replace-rollback-usd-test",
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

    long invoiceId = invoiceService.createInvoice(List.of(eurTransactionId));
    long snapshotIdBefore = invoiceService.findTransactionSnapshots(invoiceId)
            .get(0).id();

    assertThrows(
            IllegalStateException.class,
            () -> invoiceService.replaceInvoiceTransactions(
                    invoiceId, List.of(eurTransactionId, usdTransactionId))
    );

    assertEquals(
            new BigDecimal("220"),
            invoiceService.findById(invoiceId).total()
    );
    assertEquals(
            1,
            invoiceService.findTransactionSnapshots(invoiceId).size()
    );
    assertEquals(
            snapshotIdBefore,
            invoiceService.findTransactionSnapshots(invoiceId).get(0).id()
    );}

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
    void invoiceFromAllFiveFixturesTotals68570() throws Exception {
    JsonNode fixtures = objectMapper.readTree(
            Files.readString(Path.of("fixtures/task-b/seed-payloads.json"))
    );

    String[] fixtureNames = {
        "flight", "hotel", "rail", "trip_fee", "agent_call_fee"
    };

    List<Long> transactionIds = new java.util.ArrayList<>();

    for (String fixtureName : fixtureNames) {
        JsonNode fixture = fixtures.get(fixtureName);
        ObjectNode payload = (ObjectNode) objectMapper.readTree(fixture.toString());
        payload.put("external_id", fixture.get("external_id").asText()
                + "-invoice-" + System.nanoTime());

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
            .set("transaction_ids",
                    objectMapper.valueToTree(transactionIds))
            .toString();

    String invoiceResponse = mockMvc.perform(post("/invoices")
            .contentType(MediaType.APPLICATION_JSON)
            .content(invoiceRequest))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    long invoiceId = objectMapper.readTree(invoiceResponse)
            .get("id").asLong();

    mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/invoices/{id}", invoiceId)
    )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(685.7))
            .andExpect(jsonPath("$.transactions.length()").value(5));
  }

@Test
void replacingInvoiceWithMissingTransactionReturns404AndPreservesInvoice()
        throws Exception {
    long transactionId = transactionService.createTransaction(
            "http-missing-tx-" + System.nanoTime(),
            "flight",
            "EUR",
            "2026-03-11T06:40:00Z",
            new BigDecimal("220.00"),
            """
            {
              "origin": "TXL",
              "destination": "LHR",
              "fare": 220,
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

    long snapshotIdBefore = invoiceService.findTransactionSnapshots(invoiceId)
            .get(0).id();

    mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .patch("/invoices/" + invoiceId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                    {"transaction_ids":[9223372036854775807]}
                    """))
            .andExpect(status().isNotFound());

    assertEquals(
            new BigDecimal("220"),
            invoiceService.findById(invoiceId).total());
    assertEquals(
            1,
            invoiceService.findTransactionSnapshots(invoiceId).size());
    assertEquals(
            snapshotIdBefore,
            invoiceService.findTransactionSnapshots(invoiceId).get(0).id());
}

}