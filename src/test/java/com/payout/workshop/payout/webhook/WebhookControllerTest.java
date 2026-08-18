package com.payout.workshop.payout.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payout.workshop.payout.ledger.AccountBalance;
import com.payout.workshop.payout.ledger.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * This test suite IS the spec for Tier 1. Agent Mode should make all of these
 * pass without weakening any assertion below.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class WebhookControllerTest {

    private static final String SECRET = "test-secret";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("webhook.secret", () -> SECRET);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void seedAccounts() {
        accountRepository.deleteAll();
        accountRepository.save(new AccountBalance("acct-usd-1", "USD", new BigDecimal("100.0000")));
        accountRepository.save(new AccountBalance("acct-eur-1", "EUR", new BigDecimal("50.0000")));
    }

    @Test
    void rejectsRequestWithInvalidSignature() throws Exception {
        String body = payload("evt-bad-sig", "acct-usd-1", "25.00", "USD");

        mockMvc.perform(post("/webhooks/payout-status")
                        .header("Payout-Transmission-Sig", "not-a-real-signature")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsValidSignatureAndCreditsBalance() throws Exception {
        String eventId = "evt-" + Instant.now().toEpochMilli();
        String body = payload(eventId, "acct-usd-1", "25.00", "USD");

        mockMvc.perform(post("/webhooks/payout-status")
                        .header("Payout-Transmission-Sig", sign(body))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            AccountBalance account = accountRepository.findByAccountId("acct-usd-1").orElseThrow();
            assertThat(account.getBalance()).isEqualByComparingTo("125.0000");
        });
    }

    @Test
    void convertsCurrencyBeforeCrediting() throws Exception {
        String eventId = "evt-fx-" + Instant.now().toEpochMilli();
        // 10 USD credited to a EUR account should land as 9.2000 EUR (fixed test rate).
        String body = payload(eventId, "acct-eur-1", "10.00", "USD");

        mockMvc.perform(post("/webhooks/payout-status")
                        .header("Payout-Transmission-Sig", sign(body))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            AccountBalance account = accountRepository.findByAccountId("acct-eur-1").orElseThrow();
            assertThat(account.getBalance()).isEqualByComparingTo("59.2000");
        });
    }

    @Test
    void duplicateDeliveryIsAppliedExactlyOnce() throws Exception {
        String eventId = "evt-dup-" + Instant.now().toEpochMilli();
        String body = payload(eventId, "acct-usd-1", "25.00", "USD");
        String signature = sign(body);

        // Simulate payout redelivering the same event twice in quick succession.
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/webhooks/payout-status")
                            .header("Payout-Transmission-Sig", signature)
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isOk());
        }

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            AccountBalance account = accountRepository.findByAccountId("acct-usd-1").orElseThrow();
            assertThat(account.getBalance()).isEqualByComparingTo("125.0000");
        });
    }

    @Test
    void concurrentDuplicateDeliveriesAreAppliedExactlyOnce() throws Exception {
        String eventId = "evt-concurrent-" + Instant.now().toEpochMilli();
        String body = payload(eventId, "acct-usd-1", "25.00", "USD");
        String signature = sign(body);

        int concurrency = 8;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Integer>> responses = new ArrayList<>();

        try {
            // Simulate payout fanning the same delivery out across several connections at once.
            for (int i = 0; i < concurrency; i++) {
                responses.add(executor.submit(() -> {
                    startGate.await();
                    return mockMvc.perform(post("/webhooks/payout-status")
                                    .header("Payout-Transmission-Sig", signature)
                                    .contentType("application/json")
                                    .content(body))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                }));
            }
            startGate.countDown();

            for (Future<Integer> response : responses) {
                assertThat(response.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            executor.shutdownNow();
        }

        // Credited exactly once, and it stays that way once every async worker has drained.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            AccountBalance account = accountRepository.findByAccountId("acct-usd-1").orElseThrow();
            assertThat(account.getBalance()).isEqualByComparingTo("125.0000");
        });
    }

    private String payload(String eventId, String accountId, String amount, String currency) {
        return """
                {
                  "eventId": "%s",
                  "payoutId": "payout-%s",
                  "accountId": "%s",
                  "status": "COMPLETED",
                  "amount": %s,
                  "currency": "%s",
                  "occurredAt": "%s"
                }
                """.formatted(eventId, eventId, accountId, amount, currency, Instant.now());
    }

    private String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
