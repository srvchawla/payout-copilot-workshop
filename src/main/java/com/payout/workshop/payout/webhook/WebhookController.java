package com.payout.workshop.payout.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payout.workshop.payout.dto.PayoutWebhookPayload;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * LAB TASK (Tier 1): wire signature verification + idempotency + async dispatch.
 *
 * Expected flow for POST /webhooks/payout-status:
 *  1. Reject with 401 if signatureVerifier.verify(rawBody, signature) is false.
 *  2. Parse rawBody into a PayoutWebhookPayload.
 *  3. If idempotencyService.tryAcquire(payload.eventId()) is false, return 200
 *     immediately (already processed/in-flight) without republishing.
 *  4. Otherwise publish a PayoutStatusReceivedEvent and return 200 within
 *     milliseconds - do not do ledger/FX work on this thread.
 *
 * Each of those outcomes must also be counted via WebhookMetrics:
 * recordInvalidSignature() for step 1, recordDuplicate() for step 3 and
 * recordAccepted() for step 4.
 */
@RestController
public class WebhookController {

    private final SignatureVerifier signatureVerifier;
    private final IdempotencyService idempotencyService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final WebhookMetrics webhookMetrics;

    public WebhookController(SignatureVerifier signatureVerifier,
                              IdempotencyService idempotencyService,
                              ApplicationEventPublisher eventPublisher,
                              ObjectMapper objectMapper,
                              WebhookMetrics webhookMetrics) {
        this.signatureVerifier = signatureVerifier;
        this.idempotencyService = idempotencyService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.webhookMetrics = webhookMetrics;
    }

    @PostMapping("/webhooks/payout-status")
    public ResponseEntity<Void> receivePayoutStatus(
            @RequestHeader(value = "Payout-Transmission-Sig", required = false) String signature,
            @RequestBody String rawBody) throws Exception {
        throw new UnsupportedOperationException(
                "TODO(workshop): implement the webhook ingestion flow described above");
    }
}
