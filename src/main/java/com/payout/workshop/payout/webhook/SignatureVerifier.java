package com.payout.workshop.payout.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

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

    private static final Logger log = LoggerFactory.getLogger(SignatureVerifier.class);

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final WebhookProperties webhookProperties;

    public SignatureVerifier(WebhookProperties webhookProperties) {
        this.webhookProperties = webhookProperties;
    }

    public boolean verify(String rawBody, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            log.warn("Rejecting webhook: missing or blank signature header");
            return false;
        }
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signatureHeader.trim());
        } catch (IllegalArgumentException ex) {
            log.warn("Rejecting webhook: signature header is not valid hex");
            return false;
        }
        return MessageDigest.isEqual(provided, hmac(rawBody));
    }

    private byte[] hmac(String rawBody) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    webhookProperties.getSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to compute webhook signature", ex);
        }
    }
}
