package com.payout.workshop.payout.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payout.workshop.payout.dto.PayoutWebhookPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
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
 */
@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final SignatureVerifier signatureVerifier;
    private final IdempotencyService idempotencyService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public WebhookController(SignatureVerifier signatureVerifier,
                              IdempotencyService idempotencyService,
                              ApplicationEventPublisher eventPublisher,
                              ObjectMapper objectMapper) {
        this.signatureVerifier = signatureVerifier;
        this.idempotencyService = idempotencyService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/webhooks/payout-status")
    public ResponseEntity<Void> receivePayoutStatus(
            @RequestHeader(value = "Payout-Transmission-Sig", required = false) String signature,
            @RequestBody String rawBody) throws Exception {
        if (!signatureVerifier.verify(rawBody, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        PayoutWebhookPayload payload = objectMapper.readValue(rawBody, PayoutWebhookPayload.class);

        if (!idempotencyService.tryAcquire(payload.eventId())) {
            log.warn("Ignoring duplicate webhook delivery for eventId={}", payload.eventId());
            return ResponseEntity.ok().build();
        }

        eventPublisher.publishEvent(new PayoutStatusReceivedEvent(payload));
        return ResponseEntity.ok().build();
    }
}
