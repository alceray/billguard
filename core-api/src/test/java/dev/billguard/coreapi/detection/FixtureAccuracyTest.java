package dev.billguard.coreapi.detection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FixtureAccuracyTest {
    private final RecurrenceDetector detector = new RecurrenceDetector(
        new ChargeEligibility(), new CategoryRules());

    @Test
    void labelledSandboxFixtureHasNoDeterministicFalsePositivesOrNegatives() {
        List<DetectionTransaction> fixture = new ArrayList<>();
        add(fixture, "netflix", "NETFLIX.COM", "15.49", "2026-05-01", "2026-06-01", "2026-07-01");
        add(fixture, "github copilot", "GITHUB COPILOT", "8.99", "2026-07-07", "2026-07-14", "2026-07-21");
        add(fixture, "adobe creative cloud", "ADOBE CREATIVE CLOUD", "119.88",
            "2024-08-13", "2025-08-13", "2026-08-13");
        fixture.add(transaction("city hydro utility", "CITY HYDRO UTILITY", "64.73", "2026-05-30", null));
        fixture.add(transaction("city hydro utility", "CITY HYDRO UTILITY", "101.44", "2026-06-30", null));
        fixture.add(transaction("city hydro utility", "CITY HYDRO UTILITY", "83.12", "2026-07-30", null));
        add(fixture, "irregular news club", "IRREGULAR NEWS CLUB", "12.00",
            "2026-05-19", "2026-06-03", "2026-07-25");
        fixture.add(transaction("blue bottle", "BLUE BOTTLE", "5.75", "2026-08-04", null));
        fixture.add(transaction("acme payroll", "ACME PAYROLL", "-2750.00", "2026-08-08", "INCOME_SALARY"));
        fixture.add(transaction("acme payroll", "ACME PAYROLL", "-2750.00", "2026-07-08", "INCOME_SALARY"));
        fixture.add(transaction("credit card payment", "CREDIT CARD PAYMENT", "825.00", "2026-08-06",
            "LOAN_PAYMENTS_CREDIT_CARD_PAYMENT"));
        fixture.add(transaction("credit card payment", "CREDIT CARD PAYMENT", "790.00", "2026-07-06",
            "LOAN_PAYMENTS_CREDIT_CARD_PAYMENT"));

        Set<String> expected = Set.of(
            "netflix", "github copilot", "adobe creative cloud", "city hydro utility");
        Set<String> actual = detector.detect(fixture, Map.of()).stream()
            .map(DetectedCharge::merchantKey)
            .collect(Collectors.toSet());
        Set<String> falsePositives = actual.stream().filter(key -> !expected.contains(key)).collect(Collectors.toSet());
        Set<String> falseNegatives = expected.stream().filter(key -> !actual.contains(key)).collect(Collectors.toSet());

        assertThat(falsePositives).isEmpty();
        assertThat(falseNegatives).isEmpty();
    }

    private void add(
        List<DetectionTransaction> target,
        String merchantKey,
        String merchant,
        String amount,
        String... dates
    ) {
        for (String date : dates) {
            target.add(transaction(merchantKey, merchant, amount, date, null));
        }
    }

    private DetectionTransaction transaction(
        String merchantKey,
        String merchant,
        String amount,
        String date,
        String detailed
    ) {
        return new DetectionTransaction(
            UUID.randomUUID(), UUID.randomUUID(), merchantKey, merchant, merchant,
            new BigDecimal(amount), "USD", LocalDate.parse(date), false,
            detailed != null && detailed.startsWith("INCOME") ? "INCOME" : null,
            detailed, false);
    }
}
