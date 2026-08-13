package dev.billguard.coreapi.plaid.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LinkTokenResponse(
    @JsonProperty("link_token") String linkToken,
    OffsetDateTime expiration
) {
}
