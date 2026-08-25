package dev.billguard.coreapi.detection;

public record ModelClassification(
    String displayName,
    RecurringCategory category,
    double confidence,
    boolean recurringSignal,
    String reasoning,
    String promptVersion
) {
}
