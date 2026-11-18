package dev.billguard.coreapi.detection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.billguard.coreapi.config.DetectionProperties;
import dev.billguard.coreapi.detection.AiClassificationClient.ClassificationBatch;
import dev.billguard.coreapi.detection.AiClassificationClient.ClassifierMetadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CachedClassificationService {
    private static final Logger log = LoggerFactory.getLogger(CachedClassificationService.class);

    private final AiClassificationClient aiClient;
    private final MerchantClassificationRepository repository;
    private final ClassificationFingerprint fingerprint;
    private final int maxClassificationsPerRun;

    public CachedClassificationService(
        AiClassificationClient aiClient,
        MerchantClassificationRepository repository,
        ClassificationFingerprint fingerprint,
        DetectionProperties properties
    ) {
        this.aiClient = aiClient;
        this.repository = repository;
        this.fingerprint = fingerprint;
        this.maxClassificationsPerRun = properties.maxClassificationsPerRun();
    }

    public ClassificationResolution classify(List<ClassificationCandidate> candidates) {
        if (candidates.isEmpty()) {
            return ClassificationResolution.empty();
        }
        return aiClient.metadata()
            .map(metadata -> resolve(candidates, metadata))
            .orElseGet(() -> new ClassificationResolution(
                Map.of(), 0, 0, candidates.size(), true));
    }

    private ClassificationResolution resolve(
        List<ClassificationCandidate> candidates,
        ClassifierMetadata metadata
    ) {
        Map<String, MerchantClassification> cached = repository.findAll(
            candidates.stream().map(ClassificationCandidate::merchantKey).toList(),
            metadata.classifierVersion());
        Map<String, ModelClassification> resolved = new LinkedHashMap<>();
        List<ClassificationCandidate> misses = new ArrayList<>();
        Map<String, String> fingerprints = new LinkedHashMap<>();

        for (ClassificationCandidate candidate : candidates) {
            String candidateFingerprint = fingerprint.create(candidate);
            fingerprints.put(candidate.merchantKey(), candidateFingerprint);
            MerchantClassification existing = cached.get(candidate.merchantKey());
            if (existing != null && candidateFingerprint.equals(existing.fingerprint())) {
                resolved.put(candidate.merchantKey(), existing.classification());
            } else {
                misses.add(candidate);
            }
        }
        int cacheHitCount = resolved.size();

        List<ClassificationCandidate> attempted = misses.stream()
            .limit(maxClassificationsPerRun)
            .toList();
        if (attempted.isEmpty()) {
            return new ClassificationResolution(
                Map.copyOf(resolved), cacheHitCount, 0, 0, false);
        }

        ClassificationBatch batch = aiClient.classify(attempted);
        if (batch.classifierVersion() == null || batch.classifierVersion().isBlank()) {
            return new ClassificationResolution(
                Map.copyOf(resolved), cacheHitCount, 0, misses.size(), true);
        }
        // The response version produced these results, so hits read under the old one are
        // stale. Re-calling the model instead would double a run against the cap.
        boolean versionChanged = !metadata.classifierVersion().equals(batch.classifierVersion());
        String writeVersion = batch.classifierVersion();
        if (versionChanged) {
            log.info(
                "Classifier version changed during cache lookup lookupVersion={} responseVersion={}; "
                    + "discarding {} cache hits and keeping fresh results under the response version",
                metadata.classifierVersion(), batch.classifierVersion(), cacheHitCount);
            resolved.clear();
            cacheHitCount = 0;
        }

        int writes = 0;
        Map<String, ClassificationCandidate> attemptedByKey = new LinkedHashMap<>();
        attempted.forEach(candidate -> attemptedByKey.put(candidate.merchantKey(), candidate));
        for (Map.Entry<String, ModelClassification> entry : batch.results().entrySet()) {
            ClassificationCandidate candidate = attemptedByKey.get(entry.getKey());
            if (candidate == null) {
                continue;
            }
            repository.upsert(
                candidate, writeVersion, fingerprints.get(entry.getKey()),
                entry.getValue(), batch.model());
            resolved.put(entry.getKey(), entry.getValue());
            writes++;
        }
        return new ClassificationResolution(
            Map.copyOf(resolved), cacheHitCount, writes, misses.size() - writes,
            batch.degraded() || versionChanged);
    }

    public record ClassificationResolution(
        Map<String, ModelClassification> classifications,
        int cacheHits,
        int cacheWrites,
        int remainingMisses,
        boolean degraded
    ) {
        static ClassificationResolution empty() {
            return new ClassificationResolution(Map.of(), 0, 0, 0, false);
        }
    }
}
