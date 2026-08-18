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
 * Verifies the `Payout-Transmission-Sig` header as an HMAC-SHA256 (hex encoded)
 * of the raw webhook body, keyed with WebhookProperties#getSecret().
 *
 * Parsing is deliberately defensive: a missing, blank, malformed or wrong-length
 * header returns false so the caller can answer 401 - it never throws.
 * Signatures that are well-formed hex (upper or lower case) are compared with a
 * constant-time comparison (MessageDigest#isEqual).
 */
@Component
public class SignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(SignatureVerifier.class);

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int SIGNATURE_LENGTH_BYTES = 32;

    private final WebhookProperties webhookProperties;

    public SignatureVerifier(WebhookProperties webhookProperties) {
        this.webhookProperties = webhookProperties;
    }

    public boolean verify(String rawBody, String signatureHeader) {
        if (rawBody == null || signatureHeader == null || signatureHeader.isBlank()) {
            log.warn("Rejecting webhook: missing or blank signature header");
            return false;
        }

        byte[] providedSignature = decodeHex(signatureHeader.trim());
        if (providedSignature == null || providedSignature.length != SIGNATURE_LENGTH_BYTES) {
            log.warn("Rejecting webhook: signature header is not a {}-byte hex digest", SIGNATURE_LENGTH_BYTES);
            return false;
        }

        byte[] expectedSignature = hmacSha256(rawBody);
        boolean valid = MessageDigest.isEqual(expectedSignature, providedSignature);
        if (!valid) {
            log.warn("Rejecting webhook: signature verification failed");
        }
        return valid;
    }

    private byte[] decodeHex(String value) {
        try {
            return HexFormat.of().parseHex(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private byte[] hmacSha256(String rawBody) {
        String secret = webhookProperties.getSecret();
        if (secret == null || secret.isEmpty()) {
            throw new IllegalStateException("webhook.secret is not configured");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to compute HMAC-SHA256 signature", ex);
        }
    }
}
