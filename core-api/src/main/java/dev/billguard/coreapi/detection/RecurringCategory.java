package dev.billguard.coreapi.detection;

import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum RecurringCategory {
    STREAMING("streaming"),
    AI("ai"),
    SOFTWARE("software"),
    GAMING("gaming"),
    NEWS_AND_MEDIA("news_and_media"),
    FITNESS_AND_HEALTH("fitness_and_health"),
    INSURANCE("insurance"),
    STORAGE_AND_HOSTING("storage_and_hosting"),
    TELECOM("telecom"),
    UTILITIES("utilities"),
    FOOD_AND_DELIVERY("food_and_delivery"),
    SHOPPING_MEMBERSHIP("shopping_membership"),
    EDUCATION("education"),
    FINANCE("finance"),
    HOUSING("housing"),
    LOAN("loan"),
    OTHER("other");

    private final String value;

    RecurringCategory(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static RecurringCategory fromJson(String value) {
        return Arrays.stream(values())
            .filter(category -> category.value.equalsIgnoreCase(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unsupported recurring category"));
    }

    public static RecurringCategory fromValue(String value) {
        if (value == null) {
            return OTHER;
        }
        return Arrays.stream(values())
            .filter(category -> category.value.equalsIgnoreCase(value))
            .findFirst()
            .orElse(OTHER);
    }
}
