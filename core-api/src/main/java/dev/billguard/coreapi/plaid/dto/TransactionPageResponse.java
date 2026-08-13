package dev.billguard.coreapi.plaid.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TransactionPageResponse(
    List<TransactionResponse> transactions,
    @JsonProperty("next_cursor") String nextCursor
) {
}
