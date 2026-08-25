package dev.billguard.coreapi.detection;

import java.util.Comparator;
import java.util.List;

public record ChargeGroup(String merchantKey, List<DetectionTransaction> transactions) {
    public ChargeGroup {
        transactions = transactions.stream()
            .sorted(Comparator.comparing(DetectionTransaction::date))
            .toList();
    }

    public DetectionTransaction latest() {
        return transactions.getLast();
    }
}
