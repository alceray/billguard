package dev.billguard.coreapi.detection;

public record RecurrenceMetrics(
    BillingCycle cycle,
    double medianGapDays,
    double medianAbsoluteDeviation,
    double amountCoefficientOfVariation,
    double intervalRegularity,
    boolean variableAmount
) {
}
