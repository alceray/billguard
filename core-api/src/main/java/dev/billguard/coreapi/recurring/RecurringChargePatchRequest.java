package dev.billguard.coreapi.recurring;

import dev.billguard.coreapi.detection.RecurringCategory;

public record RecurringChargePatchRequest(
    Boolean recurring,
    RecurringCategory category
) {
    public boolean empty() {
        return recurring == null && category == null;
    }
}
