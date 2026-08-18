package com.payout.workshop.payout.webhook;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Micrometer counters describing what happened to inbound payout webhooks.
 *
 * <p>Counters are deliberately untagged: account IDs, event IDs and payout IDs
 * are unbounded and would blow up metric cardinality (and leak identifiers into
 * the metrics backend). Use the application log for per-event detail.
 *
 * <p>Metric names are documented in the README ("Webhook metrics").
 */
@Component
public class WebhookMetrics {

    /** A webhook passed signature + idempotency checks and was handed off for processing. */
    public static final String ACCEPTED = "payout.webhook.accepted";

    /** A webhook was rejected with 401 because its signature was missing or invalid. */
    public static final String INVALID_SIGNATURE = "payout.webhook.invalid.signature";

    /** A webhook was a redelivery of an event that was already processed or is in flight. */
    public static final String DUPLICATE = "payout.webhook.duplicate";

    /** Asynchronous processing of an accepted webhook finished successfully. */
    public static final String PROCESSING_COMPLETED = "payout.webhook.processing.completed";

    /** Asynchronous processing of an accepted webhook failed. */
    public static final String PROCESSING_FAILED = "payout.webhook.processing.failed";

    private final Counter accepted;
    private final Counter invalidSignature;
    private final Counter duplicate;
    private final Counter processingCompleted;
    private final Counter processingFailed;

    public WebhookMetrics(MeterRegistry meterRegistry) {
        this.accepted = Counter.builder(ACCEPTED)
                .description("Webhooks accepted for processing")
                .register(meterRegistry);
        this.invalidSignature = Counter.builder(INVALID_SIGNATURE)
                .description("Webhooks rejected because of a missing or invalid signature")
                .register(meterRegistry);
        this.duplicate = Counter.builder(DUPLICATE)
                .description("Webhooks skipped as duplicate deliveries")
                .register(meterRegistry);
        this.processingCompleted = Counter.builder(PROCESSING_COMPLETED)
                .description("Accepted webhooks whose processing completed successfully")
                .register(meterRegistry);
        this.processingFailed = Counter.builder(PROCESSING_FAILED)
                .description("Accepted webhooks whose processing failed")
                .register(meterRegistry);
    }

    public void recordAccepted() {
        accepted.increment();
    }

    public void recordInvalidSignature() {
        invalidSignature.increment();
    }

    public void recordDuplicate() {
        duplicate.increment();
    }

    public void recordProcessingCompleted() {
        processingCompleted.increment();
    }

    public void recordProcessingFailed() {
        processingFailed.increment();
    }
}
