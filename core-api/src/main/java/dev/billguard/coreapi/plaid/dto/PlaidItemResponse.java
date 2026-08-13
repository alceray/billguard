package dev.billguard.coreapi.plaid.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PlaidItemResponse(
    @JsonProperty("item_id") String itemId,
    @JsonProperty("institution_id") String institutionId,
    @JsonProperty("institution_name") String institutionName,
    String status,
    @JsonProperty("last_error") String lastError,
    @JsonProperty("last_synced_at") Instant lastSyncedAt,
    List<AccountResponse> accounts
) {
}
