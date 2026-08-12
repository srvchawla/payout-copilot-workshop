package com.paypal.workshop.payout.webhook;

/**
 * Published internally once a webhook has passed signature + idempotency checks.
 * Handed off so the controller can return 200 without waiting on ledger work.
 */
public record PayoutStatusReceivedEvent(
        com.paypal.workshop.payout.dto.PayoutWebhookPayload payload
) {
}
