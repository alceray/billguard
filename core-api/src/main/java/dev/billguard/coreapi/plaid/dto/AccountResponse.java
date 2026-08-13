package dev.billguard.coreapi.plaid.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.billguard.coreapi.plaid.Account;

public record AccountResponse(
    UUID id,
    @JsonProperty("plaid_account_id") String plaidAccountId,
    String name,
    @JsonProperty("official_name") String officialName,
    String type,
    String subtype,
    String mask
) {
    public static AccountResponse from(Account account) {
        return new AccountResponse(
            account.getId(), account.getPlaidAccountId(), account.getName(), account.getOfficialName(),
            account.getType(), account.getSubtype(), account.getMask());
    }
}
