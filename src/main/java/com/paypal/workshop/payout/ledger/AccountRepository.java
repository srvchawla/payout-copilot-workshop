package com.paypal.workshop.payout.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<AccountBalance, Long> {

    Optional<AccountBalance> findByAccountId(String accountId);
}
