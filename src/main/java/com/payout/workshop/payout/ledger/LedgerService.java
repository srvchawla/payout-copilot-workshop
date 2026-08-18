package com.payout.workshop.payout.ledger;

import com.payout.workshop.payout.fx.ConversionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

/**
 * LAB TASK (Tier 1): apply a COMPLETED payout to the recipient's balance.
 *
 * Requirements:
 *  - Look up the account by accountId; fail loudly (no silent no-op) if missing.
 *  - Convert `amount`/`payoutCurrency` into the account's own currency using
 *    ConversionService when they differ.
 *  - Persist the updated balance. Rely on AccountBalance's @Version field for
 *    optimistic locking - do not add your own manual locking here.
 *
 * Concurrent payouts for *different* event IDs can target the same account, so
 * a credit may lose the optimistic-lock race. Each attempt runs in its own
 * transaction and is retried a bounded number of times with jittered backoff;
 * retries re-read the account, so no credit is ever applied twice and webhook
 * idempotency (one Redis lock per event ID) is untouched.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private static final long BASE_BACKOFF_MILLIS = 10;
    private static final long MAX_BACKOFF_MILLIS = 200;

    private final AccountRepository accountRepository;
    private final ConversionService conversionService;
    private final TransactionTemplate transactionTemplate;
    private final int maxAttempts;

    public LedgerService(AccountRepository accountRepository,
                         ConversionService conversionService,
                         PlatformTransactionManager transactionManager,
                         @Value("${ledger.optimistic-lock.max-attempts:5}") int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("ledger.optimistic-lock.max-attempts must be at least 1");
        }
        this.accountRepository = accountRepository;
        this.conversionService = conversionService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.maxAttempts = maxAttempts;
    }

    public void creditAccount(String accountId, BigDecimal amount, String payoutCurrency) {
        for (int attempt = 1; ; attempt++) {
            try {
                transactionTemplate.executeWithoutResult(
                        status -> applyCredit(accountId, amount, payoutCurrency));
                return;
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= maxAttempts) {
                    log.error("Giving up crediting account {} after {} optimistic-lock failures",
                            accountId, attempt);
                    throw e;
                }
                log.warn("Optimistic lock failure crediting account {} (attempt {} of {}), retrying",
                        accountId, attempt, maxAttempts);
                backoff(attempt, accountId);
            }
        }
    }

    private void applyCredit(String accountId, BigDecimal amount, String payoutCurrency) {
        AccountBalance account = accountRepository.findByAccountId(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown account: " + accountId));
        BigDecimal credit = conversionService.convert(amount, payoutCurrency, account.getCurrency());
        account.credit(credit);
        // Flush inside this attempt's transaction so a version conflict surfaces
        // here as an OptimisticLockingFailureException and can be retried.
        accountRepository.saveAndFlush(account);
    }

    private void backoff(int attempt, String accountId) {
        long ceiling = Math.min(BASE_BACKOFF_MILLIS * attempt, MAX_BACKOFF_MILLIS);
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, ceiling + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying credit for account " + accountId, e);
        }
    }
}
