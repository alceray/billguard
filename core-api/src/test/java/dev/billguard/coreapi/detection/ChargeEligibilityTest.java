package dev.billguard.coreapi.detection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChargeEligibilityTest {
    private final ChargeEligibility eligibility = new ChargeEligibility();

    @Test
    void acceptsPostedOutflow() {
        assertThat(eligibility.isEligible(transaction("10.00", false, "FOOD_AND_DRINK", null, false)))
            .isTrue();
    }

    @Test
    void excludesPendingIncomeRefundsCardPaymentsAndMatchedOwnTransfers() {
        assertThat(eligibility.isEligible(transaction("10.00", true, null, null, false))).isFalse();
        assertThat(eligibility.isEligible(transaction("100.00", false, null, "INCOME_SALARY", false)))
            .isFalse();
        assertThat(eligibility.isEligible(transaction("10.00", false, null, "GENERAL_SERVICES_REFUND", false)))
            .isFalse();
        assertThat(eligibility.isEligible(transaction(
            "10.00", false, "LOAN_REPAYMENTS", "LOAN_REPAYMENTS_CREDIT_CARD_PAYMENT", false)))
            .isFalse();
        assertThat(eligibility.isEligible(transaction(
            "10.00", false, "LOAN_PAYMENTS", "LOAN_PAYMENTS_CAR_PAYMENT", false)))
            .isTrue();
        assertThat(eligibility.isEligible(transaction(
            "1000.00", false, "RENT_AND_UTILITIES", "RENT_AND_UTILITIES_RENT", false)))
            .isTrue();
        assertThat(eligibility.isEligible(transaction("10.00", false, "TRANSFER_OUT", null, true)))
            .isFalse();
        assertThat(eligibility.isEligible(transaction(
            "10.00", false, null, "TRANSFER_OUT_ACCOUNT_TRANSFER", true))).isFalse();
        assertThat(eligibility.isEligible(transaction("10.00", false, "FOOD_AND_DRINK", null, true)))
            .isTrue();
    }

    private DetectionTransaction transaction(
        String amount,
        boolean pending,
        String primary,
        String detailed,
        boolean ownTransfer
    ) {
        return new DetectionTransaction(
            UUID.randomUUID(), UUID.randomUUID(), "merchant", "Merchant", "Merchant",
            new BigDecimal(amount), "USD", LocalDate.of(2026, 8, 1), pending,
            primary, detailed, ownTransfer);
    }
}
