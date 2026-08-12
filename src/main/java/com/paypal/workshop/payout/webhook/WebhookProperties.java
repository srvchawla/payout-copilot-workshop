package com.paypal.workshop.payout.webhook;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "webhook")
public class WebhookProperties {

    private String secret;
    private long idempotencyTtlHours = 24;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getIdempotencyTtlHours() {
        return idempotencyTtlHours;
    }

    public void setIdempotencyTtlHours(long idempotencyTtlHours) {
        this.idempotencyTtlHours = idempotencyTtlHours;
    }
}
