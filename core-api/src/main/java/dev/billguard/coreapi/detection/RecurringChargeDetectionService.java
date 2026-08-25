package dev.billguard.coreapi.detection;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.billguard.coreapi.plaid.PlaidGatewayException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RecurringChargeDetectionService {
    private static final Logger log = LoggerFactory.getLogger(RecurringChargeDetectionService.class);

    private final RecurringChargeDetectionRepository repository;
    private final RecurrenceDetector detector;
    private final AiClassificationClient aiClient;
    private final PlaidRecurringDetectionClient plaidClient;
    private final RecurringChargeWriter writer;

    public RecurringChargeDetectionService(
        RecurringChargeDetectionRepository repository,
        RecurrenceDetector detector,
        AiClassificationClient aiClient,
        PlaidRecurringDetectionClient plaidClient,
        RecurringChargeWriter writer
    ) {
        this.repository = repository;
        this.detector = detector;
        this.aiClient = aiClient;
        this.plaidClient = plaidClient;
        this.writer = writer;
    }

    public int detectUser(UUID userId) {
        List<DetectionTransaction> transactions = repository.findTransactions(userId);
        List<DetectedCharge> baseline;
        String baselineErrorCode = null;
        try {
            baseline = plaidClient.detect(userId);
        } catch (PlaidGatewayException exception) {
            baseline = List.of();
            baselineErrorCode = exception.code();
        }

        Set<String> baselineKeys = new HashSet<>();
        baseline.stream().map(DetectedCharge::merchantKey).forEach(baselineKeys::add);
        List<ChargeGroup> candidates = detector.groupEligible(transactions).stream()
            .filter(group -> !baselineKeys.contains(group.merchantKey()))
            .toList();
        Map<String, ModelClassification> classifications = aiClient.classify(candidates);
        List<DetectedCharge> additions = detector.detectGroups(candidates, classifications);

        if (baselineErrorCode == null) {
            List<DetectedCharge> merged = merge(baseline, additions);
            writer.replaceAutomaticDetections(userId, merged);
            log.info(
                "Recurring-charge detection completed userId={} transactionCount={} baselineCount={} additionCount={} mergedCount={} modelCount={}",
                userId, transactions.size(), baseline.size(), merged.size() - baseline.size(), merged.size(),
                classifications.size());
            return merged.size();
        }

        writer.addDetections(userId, additions);
        log.warn(
            "Recurring baseline unavailable; preserving previous results and adding local detections userId={} additionCount={} code={}",
            userId, additions.size(), baselineErrorCode);
        return additions.size();
    }

    private List<DetectedCharge> merge(
        List<DetectedCharge> baseline,
        List<DetectedCharge> additions
    ) {
        Map<String, DetectedCharge> merged = new LinkedHashMap<>();
        baseline.forEach(charge -> merged.put(charge.merchantKey(), charge));
        additions.forEach(charge -> merged.putIfAbsent(charge.merchantKey(), charge));
        return List.copyOf(merged.values());
    }
}
