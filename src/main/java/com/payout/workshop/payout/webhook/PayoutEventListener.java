package com.payout.workshop.payout.webhook;

import com.payout.workshop.payout.ledger.LedgerService;
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

    private final LedgerService ledgerService;

    public PayoutEventListener(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @Async
    @EventListener
    public void onPayoutStatusReceived(PayoutStatusReceivedEvent event) {
        throw new UnsupportedOperationException(
                "TODO(workshop): apply the balance update for COMPLETED payouts");
    }
}
