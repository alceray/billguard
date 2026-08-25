package dev.billguard.coreapi.detection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record DetectionTransaction(
    UUID id,
    UUID accountId,
    String merchantKey,
    String merchantName,
    String name,
    BigDecimal amount,
    String currency,
    LocalDate date,
    boolean pending,
    String pfcPrimary,
    String pfcDetailed,
    boolean matchedOwnTransfer
) {
    public String merchantLabel() {
        return merchantName == null || merchantName.isBlank() ? name : merchantName;
    }
}
