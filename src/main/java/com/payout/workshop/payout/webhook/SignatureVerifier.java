package com.payout.workshop.payout.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

/**
 * LAB TASK (Tier 1): implement HMAC-SHA256 verification of the raw webhook body
 * against the `Payout-Transmission-Sig` header, using WebhookProperties#getSecret().
 *
 * Requirements (see .github/instructions/payout-webhook.instructions.md):
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
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    webhookProperties.getSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            byte[] expected = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            byte[] provided = HexFormat.of().parseHex(signatureHeader);
            return MessageDigest.isEqual(expected, provided);
        } catch (Exception exception) {
            return false;
        }
    }
}
