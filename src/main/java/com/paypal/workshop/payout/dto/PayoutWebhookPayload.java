package com.paypal.workshop.payout.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Wire shape of a PayPal payout status webhook.
 * status is expected to be one of: PENDING, COMPLETED, FAILED.
 */
public record PayoutWebhookPayload(
        String eventId,
        String payoutId,
        String accountId,
        String status,
        BigDecimal amount,
        String currency,
        Instant occurredAt
) {
}
