package com.paypal.workshop.payout.ledger;

import com.paypal.workshop.payout.fx.ConversionService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * LAB TASK (Tier 1): apply a COMPLETED payout to the recipient's balance.
 *
 * Requirements:
 *  - Look up the account by accountId; fail loudly (no silent no-op) if missing.
 *  - Convert `amount`/`payoutCurrency` into the account's own currency using
 *    ConversionService when they differ.
 *  - Persist the updated balance. Rely on AccountBalance's @Version field for
 *    optimistic locking - do not add your own manual locking here.
 */
@Service
public class LedgerService {

    private final AccountRepository accountRepository;
    private final ConversionService conversionService;

    public LedgerService(AccountRepository accountRepository, ConversionService conversionService) {
        this.accountRepository = accountRepository;
        this.conversionService = conversionService;
    }

    public void creditAccount(String accountId, BigDecimal amount, String payoutCurrency) {
        throw new UnsupportedOperationException(
                "TODO(workshop): convert currency if needed and credit the account balance");
    }
}
