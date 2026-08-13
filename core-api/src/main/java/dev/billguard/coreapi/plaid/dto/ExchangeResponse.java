package dev.billguard.coreapi.plaid.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ExchangeResponse(
    @JsonProperty("item_id") String itemId,
    @JsonProperty("institution_name") String institutionName,
    List<AccountResponse> accounts
) {
}
