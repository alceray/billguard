package dev.billguard.coreapi.detection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class RecurrenceDetectorTest {
    private final RecurrenceDetector detector = new RecurrenceDetector(
        new ChargeEligibility(), new CategoryRules());

    @ParameterizedTest
    @MethodSource("cycles")
    void recognizesSupportedCycles(int gap, BillingCycle expected) {
        ChargeGroup group = new ChargeGroup("merchant", List.of(
            transaction("merchant", "10.00", LocalDate.of(2025, 1, 1), "Merchant"),
            transaction("merchant", "10.00", LocalDate.of(2025, 1, 1).plusDays(gap), "Merchant"),
            transaction("merchant", "10.00", LocalDate.of(2025, 1, 1).plusDays(gap * 2L), "Merchant")));

        assertThat(detector.metrics(group).cycle()).isEqualTo(expected);
    }

    @Test
    void threeRegularChargesAreConfirmedAndTwoAreProvisional() {
        List<DetectedCharge> three = detector.detect(monthly(3, "Netflix", "15.49"), Map.of());
        List<DetectedCharge> two = detector.detect(monthly(2, "Netflix", "15.49"), Map.of());

        assertThat(three).singleElement().satisfies(charge -> {
            assertThat(charge.detectionState()).isEqualTo("confirmed");
            assertThat(charge.billingCycle()).isEqualTo(BillingCycle.MONTHLY);
            assertThat(charge.category()).isEqualTo(RecurringCategory.STREAMING);
        });
        assertThat(two).singleElement().extracting(DetectedCharge::detectionState)
            .isEqualTo("provisional");
        assertThat(two.getFirst().confidenceScore()).isLessThan(three.getFirst().confidenceScore());
    }

    @Test
    void singletonScoresNoRecurrenceEvidenceAndCarriesModelConfidenceSeparately() {
        List<DetectionTransaction> single = List.of(
            transaction("gym", "25.00", LocalDate.of(2026, 8, 1), "City Gym"));

        assertThat(detector.detect(single, Map.of())).isEmpty();

        ModelClassification model = new ModelClassification(
            "City Gym", RecurringCategory.FITNESS_AND_HEALTH, 0.82, true, "gym membership", "v1");

        assertThat(detector.detect(single, Map.of("gym", model))).singleElement().satisfies(charge -> {
            assertThat(charge.confidenceScore()).isEqualByComparingTo("0.00");
            assertThat(charge.modelConfidence()).isEqualByComparingTo("0.82");
        });
    }

    @Test
    void knownSingletonIsSuspectedButNoiseIsIgnored() {
        ModelClassification gym = new ModelClassification(
            "City Gym", RecurringCategory.FITNESS_AND_HEALTH, 0.82, true, "gym membership", "v1");
        ModelClassification coffee = new ModelClassification(
            "Corner Coffee", RecurringCategory.OTHER, 0.40, false, "one-off retail", "v1");

        assertThat(detector.detect(List.of(
            transaction("gym", "25.00", LocalDate.of(2026, 8, 1), "City Gym")), Map.of("gym", gym)))
            .singleElement().satisfies(charge -> {
                assertThat(charge.detectionState()).isEqualTo("suspected");
                assertThat(charge.billingCycle()).isNull();
                assertThat(charge.nextChargeDate()).isNull();
            });
        assertThat(detector.detect(List.of(
            transaction("coffee", "5.00", LocalDate.of(2026, 8, 1), "Corner Coffee")),
            Map.of("coffee", coffee)))
            .isEmpty();
    }

    @Test
    void irregularNearMissIsIgnored() {
        List<DetectionTransaction> irregular = List.of(
            transaction("club", "12.00", LocalDate.of(2026, 5, 19), "Local Club"),
            transaction("club", "12.00", LocalDate.of(2026, 6, 3), "Local Club"),
            transaction("club", "12.00", LocalDate.of(2026, 7, 25), "Local Club"));

        assertThat(detector.detect(irregular, Map.of())).isEmpty();
    }

    @Test
    void variableUtilityUsesAverageAndSuppressesPriceHistory() {
        List<DetectionTransaction> utility = List.of(
            transaction("hydro", "64.73", LocalDate.of(2026, 5, 30), "City Hydro Utility"),
            transaction("hydro", "101.44", LocalDate.of(2026, 6, 30), "City Hydro Utility"),
            transaction("hydro", "83.12", LocalDate.of(2026, 7, 30), "City Hydro Utility"));

        assertThat(detector.detect(utility, Map.of())).singleElement().satisfies(charge -> {
            assertThat(charge.variableAmount()).isTrue();
            assertThat(charge.amount()).isEqualByComparingTo("83.10");
            assertThat(charge.previousAmount()).isNull();
            assertThat(charge.amountChangedAt()).isNull();
        });
    }

    @Test
    void twelveThirtyDayChargesProjectTheNextMedianGap() {
        List<DetectionTransaction> charges = Stream.iterate(0, value -> value + 1).limit(12)
            .map(index -> transaction(
                "software", "10.00", LocalDate.of(2025, 8, 1).plusDays(index * 30L), "Adobe"))
            .toList();

        assertThat(detector.detect(charges, Map.of())).singleElement().satisfies(charge -> {
            assertThat(charge.detectionState()).isEqualTo("confirmed");
            assertThat(charge.billingCycle()).isEqualTo(BillingCycle.MONTHLY);
            assertThat(charge.nextChargeDate()).isEqualTo(LocalDate.of(2026, 7, 27));
        });
    }

    @Test
    void annualStreamIsConfirmed() {
        List<DetectionTransaction> charges = List.of(
            transaction("adobe", "119.88", LocalDate.of(2024, 8, 13), "Adobe"),
            transaction("adobe", "119.88", LocalDate.of(2025, 8, 13), "Adobe"),
            transaction("adobe", "119.88", LocalDate.of(2026, 8, 13), "Adobe"));

        assertThat(detector.detect(charges, Map.of())).singleElement().satisfies(charge -> {
            assertThat(charge.detectionState()).isEqualTo("confirmed");
            assertThat(charge.billingCycle()).isEqualTo(BillingCycle.ANNUAL);
            assertThat(charge.nextChargeDate()).isEqualTo(LocalDate.of(2027, 8, 13));
        });
    }

    @Test
    void stableStreamAmountChangeCreatesPriceHistoryFields() {
        List<DetectionTransaction> charges = List.of(
            transaction("software", "10.00", LocalDate.of(2026, 5, 1), "Adobe"),
            transaction("software", "10.00", LocalDate.of(2026, 6, 1), "Adobe"),
            transaction("software", "10.50", LocalDate.of(2026, 7, 1), "Adobe"));

        assertThat(detector.detect(charges, Map.of())).singleElement().satisfies(charge -> {
            assertThat(charge.variableAmount()).isFalse();
            assertThat(charge.previousAmount()).isEqualByComparingTo("10.00");
            assertThat(charge.amountChangedAt()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
        });
    }

    @Test
    void unchangedStableStreamHasNoPriceHistoryFields() {
        List<DetectionTransaction> charges = monthly(3, "Adobe", "10.00");

        assertThat(detector.detect(charges, Map.of())).singleElement().satisfies(charge -> {
            assertThat(charge.previousAmount()).isNull();
            assertThat(charge.amountChangedAt()).isNull();
        });
    }

    @Test
    void pointOfSaleSingletonIsRejectedNoMatterWhatTheModelSays() {
        ModelClassification confident = new ModelClassification(
            "Merchant", RecurringCategory.SHOPPING_MEMBERSHIP, 0.99, true, "looks recurring", "v4");

        assertThat(detector.detect(List.of(pointOfSale(
            "local market", "TST*LOCAL MARKET 1842", "FOOD_AND_DRINK", "FOOD_AND_DRINK_RESTAURANT")),
            Map.of("local market", confident))).isEmpty();
        assertThat(detector.detect(List.of(pointOfSale(
            "blue bottle coffee", "BLUE BOTTLE #47", "FOOD_AND_DRINK", "FOOD_AND_DRINK_COFFEE")),
            Map.of("blue bottle coffee", confident))).isEmpty();
        assertThat(detector.detect(List.of(pointOfSale("bookstore purchase", "BOOKSTORE PURCHASE",
            "GENERAL_MERCHANDISE", "GENERAL_MERCHANDISE_BOOKSTORES_AND_NEWSSTANDS")),
            Map.of("bookstore purchase", confident))).isEmpty();
    }

    @Test
    void lowConfidenceSingletonIsRejectedEvenWhenTheModelClaimsRecurring() {
        DetectionTransaction retail = transaction(
            "local market", "47.31", LocalDate.of(2026, 7, 19), "TST*LOCAL MARKET 1842");

        ModelClassification underFloor = new ModelClassification(
            "Local Market", RecurringCategory.OTHER, 0.59, true, "unclear", "v3");
        assertThat(detector.detect(List.of(retail), Map.of("local market", underFloor))).isEmpty();

        ModelClassification atFloor = new ModelClassification(
            "Local Market", RecurringCategory.SOFTWARE, 0.60, true, "billing merchant", "v3");
        assertThat(detector.detect(List.of(retail), Map.of("local market", atFloor)))
            .singleElement().extracting(DetectedCharge::detectionState).isEqualTo("suspected");
    }

    @Test
    void modelCanPromoteAnUnknownSingletonAndSuppliesPromptMetadata() {
        DetectionTransaction transaction = transaction(
            "unknown", "9.99", LocalDate.of(2026, 8, 1), "Opaque Merchant");
        ModelClassification model = new ModelClassification(
            "Useful App", RecurringCategory.SOFTWARE, 0.92, true, "Signup-like charge", "v1");

        assertThat(detector.detect(List.of(transaction), Map.of("unknown", model)))
            .singleElement().satisfies(charge -> {
                assertThat(charge.displayName()).isEqualTo("Useful App");
                assertThat(charge.categorySource()).isEqualTo("model");
                assertThat(charge.promptVersion()).isEqualTo("v1");
            });
    }

    static Stream<Arguments> cycles() {
        return Stream.of(
            Arguments.of(7, BillingCycle.WEEKLY),
            Arguments.of(14, BillingCycle.BIWEEKLY),
            Arguments.of(30, BillingCycle.MONTHLY),
            Arguments.of(91, BillingCycle.QUARTERLY),
            Arguments.of(365, BillingCycle.ANNUAL));
    }

    private List<DetectionTransaction> monthly(int count, String merchant, String amount) {
        return Stream.iterate(0, value -> value + 1).limit(count)
            .map(index -> transaction(
                merchant.toLowerCase(), amount, LocalDate.of(2026, 5, 1).plusMonths(index), merchant))
            .toList();
    }

    private DetectionTransaction transaction(
        String merchantKey,
        String amount,
        LocalDate date,
        String merchant
    ) {
        return new DetectionTransaction(
            UUID.randomUUID(), UUID.randomUUID(), merchantKey, merchant, merchant,
            new BigDecimal(amount), "USD", date, false, null, null, false);
    }

    private DetectionTransaction pointOfSale(
        String merchantKey,
        String merchant,
        String pfcPrimary,
        String pfcDetailed
    ) {
        return new DetectionTransaction(
            UUID.randomUUID(), UUID.randomUUID(), merchantKey, merchant, merchant,
            new BigDecimal("12.00"), "USD", LocalDate.of(2026, 8, 1), false,
            pfcPrimary, pfcDetailed, false);
    }
}
