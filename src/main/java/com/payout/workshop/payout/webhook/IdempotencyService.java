package com.payout.workshop.payout.webhook;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * LAB TASK (Tier 1): make webhook processing idempotent using Redis.
 *
 * Requirements:
 *  - Acquiring a lock for an eventId must be a single ATOMIC operation
 *    (e.g. StringRedisTemplate#opsForValue().setIfAbsent(key, value, ttl)).
 *    Do NOT check-then-set as two separate calls - that reintroduces the race
 *    condition this class exists to prevent.
 *  - TTL should come from WebhookProperties#getIdempotencyTtlHours().
 *  - Return true if this call acquired the lock (i.e. first time seeing this
 *    eventId), false if it was already processed/in-flight.
 */
@Component
public class IdempotencyService {

    private static final String KEY_PREFIX = "payout-webhook:processed:";

    private final StringRedisTemplate redisTemplate;
    private final WebhookProperties webhookProperties;

    public IdempotencyService(StringRedisTemplate redisTemplate, WebhookProperties webhookProperties) {
        this.redisTemplate = redisTemplate;
        this.webhookProperties = webhookProperties;
    }

    public boolean tryAcquire(String eventId) {
        Duration ttl = Duration.ofHours(webhookProperties.getIdempotencyTtlHours());
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + eventId, "1", ttl);
        return Boolean.TRUE.equals(acquired);
    }
}
