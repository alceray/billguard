package dev.billguard.coreapi.detection;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.billguard.coreapi.detection.CachedClassificationService.ClassificationResolution;
import dev.billguard.coreapi.plaid.PlaidGatewayException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RecurringChargeDetectionService {
    private static final Logger log = LoggerFactory.getLogger(RecurringChargeDetectionService.class);

    private final RecurringChargeDetectionRepository repository;
    private final RecurrenceDetector detector;
    private final CachedClassificationService classificationService;
    private final ChargeEligibility eligibility;
    private final CategoryRules categoryRules;
    private final PlaidRecurringDetectionClient plaidClient;
    private final RecurringChargeWriter writer;

    public RecurringChargeDetectionService(
        RecurringChargeDetectionRepository repository,
        RecurrenceDetector detector,
        CachedClassificationService classificationService,
        ChargeEligibility eligibility,
        CategoryRules categoryRules,
        PlaidRecurringDetectionClient plaidClient,
        RecurringChargeWriter writer
    ) {
        this.repository = repository;
        this.detector = detector;
        this.classificationService = classificationService;
        this.eligibility = eligibility;
        this.categoryRules = categoryRules;
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
        List<ChargeGroup> detectionGroups = detector.groupEligible(transactions).stream()
            .filter(group -> !baselineKeys.contains(group.merchantKey()))
            .toList();
        List<ClassificationCandidate> classificationCandidates = detectionGroups.stream()
            .filter(this::canBenefitFromClassification)
            .sorted(Comparator
                .comparingInt((ChargeGroup group) -> group.transactions().size()).reversed()
                .thenComparing(ChargeGroup::merchantKey))
            .map(ClassificationCandidate::from)
            .toList();
        ClassificationResolution resolution = classificationService.classify(classificationCandidates);
        List<DetectedCharge> additions = detector.detectGroups(
            detectionGroups, resolution.classifications());

        if (baselineErrorCode == null) {
            List<DetectedCharge> merged = merge(baseline, additions);
            writer.replaceAutomaticDetections(userId, merged);
            log.info(
                "Recurring-charge detection completed userId={} transactionCount={} baselineCount={} additionCount={} mergedCount={} classificationCandidateCount={} cacheHitCount={} cacheWriteCount={} remainingClassificationMissCount={} degraded={}",
                userId, transactions.size(), baseline.size(), merged.size() - baseline.size(), merged.size(),
                classificationCandidates.size(), resolution.cacheHits(), resolution.cacheWrites(),
                resolution.remainingMisses(), resolution.degraded());
            return merged.size();
        }

        writer.addDetections(userId, additions);
        log.warn(
            "Recurring baseline unavailable; preserving previous results and adding local detections userId={} additionCount={} classificationCandidateCount={} cacheHitCount={} cacheWriteCount={} remainingClassificationMissCount={} degraded={} code={}",
            userId, additions.size(), classificationCandidates.size(), resolution.cacheHits(),
            resolution.cacheWrites(), resolution.remainingMisses(), resolution.degraded(), baselineErrorCode);
        return additions.size();
    }

    private boolean canBenefitFromClassification(ChargeGroup group) {
        DetectionTransaction latest = group.latest();
        if (group.transactions().size() == 1
            && !eligibility.allowsSingletonPromotion(latest.pfcPrimary(), latest.pfcDetailed())) {
            return false;
        }
        return group.transactions().size() < 2
            || categoryRules.categorize(
                latest.merchantLabel(), latest.pfcPrimary(), latest.pfcDetailed())
                == RecurringCategory.OTHER;
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
