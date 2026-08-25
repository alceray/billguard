package dev.billguard.coreapi.recurring;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AccuracyResponse(
    @JsonProperty("system_detected") int systemDetected,
    @JsonProperty("confirmed_feedback") int confirmedFeedback,
    @JsonProperty("false_positives") int falsePositives,
    @JsonProperty("false_negatives") int falseNegatives,
    @JsonProperty("precision_feedback") BigDecimal precisionFeedback
) {
}
