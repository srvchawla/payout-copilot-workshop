package com.payout.workshop.payout.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Wire shape of a payout payout status webhook.
 * status is expected to be one of: PENDING, COMPLETED, FAILED.
 */
public record PayoutWebhookPayload(
        @NotBlank String eventId,
        String payoutId,
        @NotBlank String accountId,
        @NotBlank String status,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
        @NotBlank String currency,
        Instant occurredAt
) {
}
