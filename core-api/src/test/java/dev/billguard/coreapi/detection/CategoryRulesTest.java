package dev.billguard.coreapi.detection;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryRulesTest {
    private final CategoryRules rules = new CategoryRules();

    @Test
    void unknownModelCategoryFallsBackToOther() {
        assertThat(RecurringCategory.fromValue("Video Streaming Deluxe"))
            .isEqualTo(RecurringCategory.OTHER);
    }

    @ParameterizedTest
    @MethodSource("categories")
    void mapsMerchantAndPfcSignals(String merchant, String detailed, RecurringCategory expected) {
        assertThat(rules.categorize(merchant, null, detailed)).isEqualTo(expected);
    }

    static Stream<Arguments> categories() {
        return Stream.of(
            Arguments.of("Netflix", "ENTERTAINMENT_TV_AND_MOVIES", RecurringCategory.STREAMING),
            Arguments.of("OpenAI", null, RecurringCategory.AI),
            Arguments.of("Adobe", null, RecurringCategory.SOFTWARE),
            Arguments.of("Xbox", null, RecurringCategory.GAMING),
            Arguments.of("Daily Newspaper", null, RecurringCategory.NEWS_AND_MEDIA),
            Arguments.of("City Gym", null, RecurringCategory.FITNESS_AND_HEALTH),
            Arguments.of("Acme Insurance", "GENERAL_SERVICES_INSURANCE", RecurringCategory.INSURANCE),
            Arguments.of("Dropbox", "GENERAL_SERVICES_STORAGE", RecurringCategory.STORAGE_AND_HOSTING),
            Arguments.of("Broadband", "RENT_AND_UTILITIES_INTERNET_AND_CABLE", RecurringCategory.TELECOM),
            Arguments.of("City Hydro", "RENT_AND_UTILITIES_GAS_AND_ELECTRICITY", RecurringCategory.UTILITIES),
            Arguments.of("DoorDash", null, RecurringCategory.FOOD_AND_DELIVERY),
            Arguments.of("Costco", null, RecurringCategory.SHOPPING_MEMBERSHIP),
            Arguments.of("Online Course", null, RecurringCategory.EDUCATION),
            Arguments.of("Credit Monitoring", null, RecurringCategory.FINANCE),
            Arguments.of("Landlord", "RENT_AND_UTILITIES_RENT", RecurringCategory.HOUSING),
            Arguments.of("Auto Loan", "LOAN_PAYMENTS_CAR_PAYMENT", RecurringCategory.LOAN),
            Arguments.of("Local Store", "GENERAL_MERCHANDISE_OTHER_GENERAL_MERCHANDISE",
                RecurringCategory.OTHER));
    }
}
