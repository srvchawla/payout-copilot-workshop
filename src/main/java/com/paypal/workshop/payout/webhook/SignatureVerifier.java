package com.paypal.workshop.payout.webhook;

import org.springframework.stereotype.Component;

/**
 * LAB TASK (Tier 1): implement HMAC-SHA256 verification of the raw webhook body
 * against the `PayPal-Transmission-Sig` header, using WebhookProperties#getSecret().
 *
 * Requirements (see .github/instructions/paypal-webhook.instructions.md):
 *  - Use HMAC-SHA256 over the exact raw request body bytes.
 *  - Compare digests using a constant-time comparison (MessageDigest.isEqual),
 *    never String#equals or Arrays.equals.
 *  - Return false (do not throw) for a missing/blank signature header.
 */
@Component
public class SignatureVerifier {

    private final WebhookProperties webhookProperties;

    public SignatureVerifier(WebhookProperties webhookProperties) {
        this.webhookProperties = webhookProperties;
    }

    public boolean verify(String rawBody, String signatureHeader) {
        throw new UnsupportedOperationException(
                "TODO(workshop): implement HMAC-SHA256 signature verification");
    }
}
