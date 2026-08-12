package com.paypal.workshop.payout.fx;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Fixed-rate stub standing in for a real FX provider so the workshop can focus
 * on webhook/idempotency concerns. Rates are illustrative only, not live.
 */
@Component
public class ConversionService {

    private static final Map<String, BigDecimal> USD_RATES = Map.of(
            "USD", BigDecimal.ONE,
            "EUR", new BigDecimal("0.92"),
            "GBP", new BigDecimal("0.79")
    );

    public BigDecimal convert(BigDecimal amount, String fromCurrency, String toCurrency) {
        if (fromCurrency.equals(toCurrency)) {
            return amount;
        }
        BigDecimal fromRate = rateFor(fromCurrency);
        BigDecimal toRate = rateFor(toCurrency);
        BigDecimal amountInUsd = amount.divide(fromRate, 10, RoundingMode.HALF_UP);
        return amountInUsd.multiply(toRate).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal rateFor(String currency) {
        BigDecimal rate = USD_RATES.get(currency);
        if (rate == null) {
            throw new IllegalArgumentException("Unsupported currency: " + currency);
        }
        return rate;
    }
}
