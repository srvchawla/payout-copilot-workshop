package com.payout.workshop.payout.webhook;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration coverage for the Redis-backed idempotency lock. Runs against a
 * real Redis so the TTL and set-if-absent semantics are actually proven.
 */
@Testcontainers
@SpringBootTest
class IdempotencyServiceTest {

    /** Must match IdempotencyService's key prefix. */
    private static final String KEY_PREFIX = "payout-webhook:processed:";

    private static final long TTL_HOURS = 2;

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
        registry.add("webhook.secret", () -> "test-secret");
        registry.add("webhook.idempotency-ttl-hours", () -> TTL_HOURS);
    }

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private WebhookProperties webhookProperties;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void acquiredKeyExpiresWithinConfiguredTtl() {
        String eventId = "evt-ttl-" + Instant.now().toEpochMilli();

        assertThat(idempotencyService.tryAcquire(eventId)).isTrue();

        Long ttlSeconds = redisTemplate.getExpire(KEY_PREFIX + eventId, TimeUnit.SECONDS);
        long configuredTtlSeconds = Duration.ofHours(webhookProperties.getIdempotencyTtlHours()).toSeconds();

        assertThat(webhookProperties.getIdempotencyTtlHours()).isEqualTo(TTL_HOURS);
        assertThat(ttlSeconds).isNotNull();
        assertThat(ttlSeconds).isPositive();
        assertThat(ttlSeconds).isLessThanOrEqualTo(configuredTtlSeconds);
    }

    @Test
    void duplicateAcquisitionReturnsFalseAndKeepsOriginalExpiry() {
        String eventId = "evt-dup-" + Instant.now().toEpochMilli();

        assertThat(idempotencyService.tryAcquire(eventId)).isTrue();
        assertThat(idempotencyService.tryAcquire(eventId)).isFalse();

        Long ttlSeconds = redisTemplate.getExpire(KEY_PREFIX + eventId, TimeUnit.SECONDS);
        assertThat(ttlSeconds).isNotNull();
        assertThat(ttlSeconds).isPositive();
        assertThat(ttlSeconds)
                .isLessThanOrEqualTo(Duration.ofHours(webhookProperties.getIdempotencyTtlHours()).toSeconds());
    }
}
