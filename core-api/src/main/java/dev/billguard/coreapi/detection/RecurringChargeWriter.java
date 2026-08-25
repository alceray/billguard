package dev.billguard.coreapi.detection;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecurringChargeWriter {
    private final RecurringChargeDetectionRepository repository;

    public RecurringChargeWriter(RecurringChargeDetectionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void replaceAutomaticDetections(UUID userId, List<DetectedCharge> detected) {
        repository.removeAutomaticExcept(
            userId, detected.stream().map(DetectedCharge::merchantKey).distinct().toList());
        addDetections(userId, detected);
    }

    @Transactional
    public void addDetections(UUID userId, List<DetectedCharge> detected) {
        for (DetectedCharge charge : detected) {
            repository.upsert(userId, charge);
        }
    }
}
