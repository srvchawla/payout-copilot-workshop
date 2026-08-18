package com.payout.workshop.payout.webhook;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookMetricsTest {

    private SimpleMeterRegistry registry;
    private WebhookMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new WebhookMetrics(registry);
    }

    @Test
    void countersStartAtZero() {
        assertThat(count(WebhookMetrics.ACCEPTED)).isZero();
        assertThat(count(WebhookMetrics.INVALID_SIGNATURE)).isZero();
        assertThat(count(WebhookMetrics.DUPLICATE)).isZero();
        assertThat(count(WebhookMetrics.PROCESSING_COMPLETED)).isZero();
        assertThat(count(WebhookMetrics.PROCESSING_FAILED)).isZero();
    }

    @Test
    void eachRecordMethodIncrementsOnlyItsOwnCounter() {
        metrics.recordAccepted();
        metrics.recordAccepted();
        metrics.recordInvalidSignature();
        metrics.recordDuplicate();
        metrics.recordProcessingCompleted();
        metrics.recordProcessingFailed();

        assertThat(count(WebhookMetrics.ACCEPTED)).isEqualTo(2.0);
        assertThat(count(WebhookMetrics.INVALID_SIGNATURE)).isEqualTo(1.0);
        assertThat(count(WebhookMetrics.DUPLICATE)).isEqualTo(1.0);
        assertThat(count(WebhookMetrics.PROCESSING_COMPLETED)).isEqualTo(1.0);
        assertThat(count(WebhookMetrics.PROCESSING_FAILED)).isEqualTo(1.0);
    }

    @Test
    void countersCarryNoHighCardinalityTags() {
        metrics.recordAccepted();
        metrics.recordInvalidSignature();
        metrics.recordDuplicate();
        metrics.recordProcessingCompleted();
        metrics.recordProcessingFailed();

        assertThat(registry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags()).isEmpty());
    }

    private double count(String name) {
        Counter counter = registry.find(name).counter();
        assertThat(counter).as("counter %s is registered", name).isNotNull();
        return counter.count();
    }
}
