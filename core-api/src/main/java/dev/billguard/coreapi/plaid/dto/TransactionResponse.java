package dev.billguard.coreapi.plaid.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TransactionResponse(
    UUID id,
    @JsonProperty("plaid_transaction_id") String plaidTransactionId,
    BigDecimal amount,
    String currency,
    @JsonProperty("merchant_name") String merchantName,
    @JsonProperty("merchant_key") String merchantKey,
    String name,
    LocalDate date,
    OffsetDateTime datetime,
    boolean pending,
    @JsonProperty("account_name") String accountName,
    @JsonProperty("institution_name") String institutionName,
    boolean recurring
) {
}
