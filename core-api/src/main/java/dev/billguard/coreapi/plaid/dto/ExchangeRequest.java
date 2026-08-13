package dev.billguard.coreapi.plaid.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ExchangeRequest(
    @NotBlank
    @Size(max = 512)
    @JsonProperty("public_token") String publicToken,

    @Size(max = 128)
    @JsonProperty("institution_id") String institutionId,

    @Size(max = 200)
    @JsonProperty("institution_name") String institutionName
) {
}
