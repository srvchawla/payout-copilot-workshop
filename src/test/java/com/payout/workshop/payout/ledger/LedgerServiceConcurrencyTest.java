package com.payout.workshop.payout.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.OptimisticLockingFailureException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Concurrent COMPLETED payouts carrying *different* event IDs can target the
 * same account, so ledger writes race on AccountBalance's @Version column.
 * These tests reproduce that conflict at the persistence layer and prove that
 * LedgerService retries it instead of dropping the credit.
 */
@Testcontainers
@SpringBootTest
class LedgerServiceConcurrencyTest {

    private static final String ACCOUNT_ID = "acct-lock-1";
    private static final BigDecimal STARTING_BALANCE = new BigDecimal("100.0000");
    private static final BigDecimal CREDIT_AMOUNT = new BigDecimal("10.00");
    private static final int CONCURRENT_PAYOUTS = 6;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    @BeforeEach
    void seedAccount() {
        accountRepository.deleteAll();
        accountRepository.save(new AccountBalance(ACCOUNT_ID, "USD", STARTING_BALANCE));
    }

    @Test
    void staleWriteFailsTheOptimisticLock() {
        AccountBalance first = accountRepository.findByAccountId(ACCOUNT_ID).orElseThrow();
        AccountBalance stale = accountRepository.findByAccountId(ACCOUNT_ID).orElseThrow();

        first.credit(CREDIT_AMOUNT);
        accountRepository.saveAndFlush(first);

        stale.credit(CREDIT_AMOUNT);
        assertThatThrownBy(() -> accountRepository.saveAndFlush(stale))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void concurrentCreditsToTheSameAccountAreAllApplied() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_PAYOUTS);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();

        try {
            for (int i = 0; i < CONCURRENT_PAYOUTS; i++) {
                results.add(executor.submit(() -> {
                    startGate.await();
                    ledgerService.creditAccount(ACCOUNT_ID, CREDIT_AMOUNT, "USD");
                    return null;
                }));
            }
            startGate.countDown();

            for (Future<?> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        AccountBalance account = accountRepository.findByAccountId(ACCOUNT_ID).orElseThrow();
        assertThat(account.getBalance())
                .isEqualByComparingTo(STARTING_BALANCE.add(
                        CREDIT_AMOUNT.multiply(BigDecimal.valueOf(CONCURRENT_PAYOUTS))));
        // One persisted update per payout - a retry re-reads the row, it never
        // replays a credit that already landed.
        assertThat(account.getVersion()).isEqualTo(CONCURRENT_PAYOUTS);
    }
}
