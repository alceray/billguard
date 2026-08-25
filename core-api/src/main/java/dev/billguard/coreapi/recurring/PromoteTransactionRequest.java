package dev.billguard.coreapi.recurring;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.billguard.coreapi.detection.RecurringCategory;
import jakarta.validation.constraints.NotNull;

public record PromoteTransactionRequest(
    @NotNull @JsonProperty("transaction_id") UUID transactionId,
    RecurringCategory category
) {
}
