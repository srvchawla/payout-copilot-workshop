package com.payout.workshop.payout.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signature parsing must never blow up on attacker-controlled header values:
 * every malformed encoding is a plain "not verified", so the controller can
 * answer 401 instead of 500.
 */
class SignatureVerifierTest {

    private static final String SECRET = "test-secret";
    private static final String BODY = "{\"eventId\":\"evt-1\"}";

    private SignatureVerifier signatureVerifier;

    @BeforeEach
    void setUp() {
        WebhookProperties properties = new WebhookProperties();
        properties.setSecret(SECRET);
        signatureVerifier = new SignatureVerifier(properties);
    }

    @Test
    void acceptsValidLowercaseHexSignature() {
        assertThat(signatureVerifier.verify(BODY, sign(BODY))).isTrue();
    }

    @Test
    void acceptsValidUppercaseHexSignature() {
        assertThat(signatureVerifier.verify(BODY, sign(BODY).toUpperCase(Locale.ROOT))).isTrue();
    }

    @Test
    void rejectsMissingSignatureHeader() {
        assertThat(signatureVerifier.verify(BODY, null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    void rejectsBlankSignatureHeader(String signature) {
        assertThat(signatureVerifier.verify(BODY, signature)).isFalse();
    }

    @Test
    void rejectsNonHexSignature() {
        assertThat(signatureVerifier.verify(BODY, "not-a-real-signature")).isFalse();
    }

    @Test
    void rejectsSignatureWithNonHexCharactersAtCorrectLength() {
        String signature = sign(BODY);
        String malformed = "zz" + signature.substring(2);

        assertThat(malformed).hasSameSizeAs(signature);
        assertThat(signatureVerifier.verify(BODY, malformed)).isFalse();
    }

    @Test
    void rejectsOddLengthHexSignature() {
        assertThat(signatureVerifier.verify(BODY, sign(BODY).substring(1))).isFalse();
    }

    @Test
    void rejectsTooShortSignature() {
        assertThat(signatureVerifier.verify(BODY, sign(BODY).substring(0, 32))).isFalse();
    }

    @Test
    void rejectsTooLongSignature() {
        assertThat(signatureVerifier.verify(BODY, sign(BODY) + "00")).isFalse();
    }

    @Test
    void rejectsWellFormedSignatureForADifferentBody() {
        assertThat(signatureVerifier.verify(BODY, sign("{\"eventId\":\"evt-2\"}"))).isFalse();
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
