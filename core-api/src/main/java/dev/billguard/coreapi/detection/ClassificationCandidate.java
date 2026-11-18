package dev.billguard.coreapi.detection;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ClassificationCandidate(
    @JsonProperty("merchant_key") String merchantKey,
    @JsonProperty("merchant_name") String merchantName,
    @JsonProperty("pfc_primary") String pfcPrimary,
    @JsonProperty("pfc_detailed") String pfcDetailed
) {
    public static ClassificationCandidate from(ChargeGroup group) {
        DetectionTransaction latest = group.latest();
        String merchantName = latest.merchantLabel();
        if (merchantName == null || merchantName.isBlank()) {
            merchantName = group.merchantKey();
        }
        return new ClassificationCandidate(
            group.merchantKey(), merchantName, latest.pfcPrimary(), latest.pfcDetailed());
    }
}
