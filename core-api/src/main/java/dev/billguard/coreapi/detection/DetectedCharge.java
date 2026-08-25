package dev.billguard.coreapi.detection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record DetectedCharge(
    String merchantKey,
    String merchantName,
    String displayName,
    BigDecimal amount,
    BigDecimal previousAmount,
    String currency,
    BillingCycle billingCycle,
    LocalDate nextChargeDate,
    BigDecimal confidenceScore,
    String detectionState,
    RecurringCategory category,
    String categorySource,
    Instant amountChangedAt,
    boolean variableAmount,
    UUID sourceTransactionId,
    LocalDate firstSeenAt,
    LocalDate lastSeenAt,
    String promptVersion,
    String reasoning
) {
}
