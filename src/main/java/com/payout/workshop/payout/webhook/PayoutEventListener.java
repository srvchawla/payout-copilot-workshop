package com.payout.workshop.payout.webhook;

import com.payout.workshop.payout.dto.PayoutWebhookPayload;
import com.payout.workshop.payout.ledger.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * LAB TASK (Tier 1): react to a verified, deduplicated webhook and apply the
 * balance update off the request thread.
 *
 * Requirements:
 *  - Must run @Async so the controller isn't blocked on ledger/FX work.
 *  - Only COMPLETED payouts should credit the ledger; ignore other statuses.
 *  - Delegate the actual balance math (incl. currency conversion) to LedgerService.
 */
@Component
public class PayoutEventListener {

    private static final Logger log = LoggerFactory.getLogger(PayoutEventListener.class);

    private static final String COMPLETED_STATUS = "COMPLETED";

    private final LedgerService ledgerService;

    public PayoutEventListener(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @Async
    @EventListener
    public void onPayoutStatusReceived(PayoutStatusReceivedEvent event) {
        PayoutWebhookPayload payload = event.payload();
        if (!COMPLETED_STATUS.equalsIgnoreCase(payload.status())) {
            log.debug("Ignoring payout status {} for eventId={}", payload.status(), payload.eventId());
            return;
        }
        ledgerService.creditAccount(payload.accountId(), payload.amount(), payload.currency());
    }
}
