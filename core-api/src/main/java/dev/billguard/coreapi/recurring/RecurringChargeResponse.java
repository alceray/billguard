package dev.billguard.coreapi.recurring;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RecurringChargeResponse(
    UUID id,
    @JsonProperty("merchant_key") String merchantKey,
    @JsonProperty("merchant_name") String merchantName,
    @JsonProperty("display_name") String displayName,
    BigDecimal amount,
    @JsonProperty("previous_amount") BigDecimal previousAmount,
    String currency,
    @JsonProperty("billing_cycle") String billingCycle,
    @JsonProperty("next_charge_date") LocalDate nextChargeDate,
    @JsonProperty("confidence_score") BigDecimal confidenceScore,
    @JsonProperty("detection_state") String detectionState,
    String category,
    @JsonProperty("category_source") String categorySource,
    @JsonProperty("amount_changed_at") Instant amountChangedAt,
    @JsonProperty("is_variable_amount") boolean variableAmount,
    @JsonProperty("first_seen_at") LocalDate firstSeenAt,
    @JsonProperty("last_seen_at") LocalDate lastSeenAt,
    @JsonProperty("user_override") Boolean userOverride,
    String group,
    @JsonProperty("monthly_equivalent") BigDecimal monthlyEquivalent
) {
}
