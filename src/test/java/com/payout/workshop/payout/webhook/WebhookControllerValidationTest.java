package com.payout.workshop.payout.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Payload validation spec for POST /webhooks/payout-status. Signature
 * verification and idempotency are stubbed out here so that only the
 * request-shape rules are under test.
 */
@WebMvcTest(WebhookController.class)
class WebhookControllerValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SignatureVerifier signatureVerifier;

    @MockBean
    private IdempotencyService idempotencyService;

    @BeforeEach
    void stubCollaborators() {
        given(signatureVerifier.verify(anyString(), any())).willReturn(true);
        given(idempotencyService.tryAcquire(anyString())).willReturn(true);
    }

    @Test
    void acceptsWellFormedPayload() throws Exception {
        mockMvc.perform(post("/webhooks/payout-status")
                        .header("Payout-Transmission-Sig", "signature")
                        .contentType("application/json")
                        .content(payload("""
                                "eventId": "evt-1",
                                "accountId": "acct-usd-1",
                                "status": "COMPLETED",
                                "amount": 25.00,
                                "currency": "USD"
                                """)))
                .andExpect(status().isOk());

        verify(idempotencyService).tryAcquire("evt-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // missing eventId
            """
            "accountId": "acct-usd-1", "status": "COMPLETED", "amount": 25.00, "currency": "USD"
            """,
            // blank eventId
            """
            "eventId": " ", "accountId": "acct-usd-1", "status": "COMPLETED", "amount": 25.00, "currency": "USD"
            """,
            // missing accountId
            """
            "eventId": "evt-1", "status": "COMPLETED", "amount": 25.00, "currency": "USD"
            """,
            // missing status
            """
            "eventId": "evt-1", "accountId": "acct-usd-1", "amount": 25.00, "currency": "USD"
            """,
            // missing amount
            """
            "eventId": "evt-1", "accountId": "acct-usd-1", "status": "COMPLETED", "currency": "USD"
            """,
            // missing currency
            """
            "eventId": "evt-1", "accountId": "acct-usd-1", "status": "COMPLETED", "amount": 25.00
            """,
            // zero amount
            """
            "eventId": "evt-1", "accountId": "acct-usd-1", "status": "COMPLETED", "amount": 0, "currency": "USD"
            """,
            // negative amount
            """
            "eventId": "evt-1", "accountId": "acct-usd-1", "status": "COMPLETED", "amount": -25.00, "currency": "USD"
            """
    })
    void rejectsIncompleteOrInvalidPayloadWithBadRequest(String fields) throws Exception {
        mockMvc.perform(post("/webhooks/payout-status")
                        .header("Payout-Transmission-Sig", "signature")
                        .contentType("application/json")
                        .content(payload(fields)))
                .andExpect(status().isBadRequest());

        verify(idempotencyService, never()).tryAcquire(anyString());
    }

    @Test
    void rejectsUnparseableJsonWithBadRequest() throws Exception {
        mockMvc.perform(post("/webhooks/payout-status")
                        .header("Payout-Transmission-Sig", "signature")
                        .contentType("application/json")
                        .content("{ not json"))
                .andExpect(status().isBadRequest());

        verify(idempotencyService, never()).tryAcquire(anyString());
    }

    @Test
    void invalidSignatureStillTakesPrecedenceOverValidation() throws Exception {
        given(signatureVerifier.verify(anyString(), any())).willReturn(false);

        mockMvc.perform(post("/webhooks/payout-status")
                        .header("Payout-Transmission-Sig", "bad-signature")
                        .contentType("application/json")
                        .content("{ not json"))
                .andExpect(status().isUnauthorized());
    }

    private String payload(String fields) {
        return "{ %s }".formatted(fields);
    }
}
