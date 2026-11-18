package dev.billguard.coreapi.detection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.billguard.coreapi.config.DetectionProperties;
import dev.billguard.coreapi.detection.AiClassificationClient.ClassificationBatch;
import dev.billguard.coreapi.detection.AiClassificationClient.ClassifierMetadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CachedClassificationServiceTest {
    @Mock
    private AiClassificationClient aiClient;

    @Mock
    private MerchantClassificationRepository repository;

    @Mock
    private ClassificationFingerprint fingerprint;

    private final Map<String, MerchantClassification> stored = new LinkedHashMap<>();
    private final List<Integer> batchSizes = new ArrayList<>();

    private void configureCacheRepository() {
        when(fingerprint.create(any())).thenAnswer(invocation -> {
            ClassificationCandidate candidate = invocation.getArgument(0);
            return "fingerprint:" + candidate.merchantName();
        });
        when(repository.findAll(anyList(), anyString())).thenAnswer(invocation -> {
            List<String> keys = invocation.getArgument(0);
            String version = invocation.getArgument(1);
            Map<String, MerchantClassification> matches = new LinkedHashMap<>();
            for (String key : keys) {
                MerchantClassification row = stored.get(version + ":" + key);
                if (row != null) {
                    matches.put(key, row);
                }
            }
            return matches;
        });
        doAnswer(invocation -> {
            ClassificationCandidate candidate = invocation.getArgument(0);
            String version = invocation.getArgument(1);
            String candidateFingerprint = invocation.getArgument(2);
            ModelClassification classification = invocation.getArgument(3);
            String model = invocation.getArgument(4);
            stored.put(version + ":" + candidate.merchantKey(), new MerchantClassification(
                candidate.merchantKey(), version, candidateFingerprint, classification, model));
            return null;
        }).when(repository).upsert(any(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void cappedRunsConvergeThenUseOnlyCacheHits() {
        configureCacheRepository();
        String version = "v4/c1/model";
        when(aiClient.metadata()).thenReturn(Optional.of(
            new ClassifierMetadata(version, "model", "v4")));
        when(aiClient.classify(anyList())).thenAnswer(invocation -> {
            List<ClassificationCandidate> candidates = invocation.getArgument(0);
            batchSizes.add(candidates.size());
            Map<String, ModelClassification> results = new LinkedHashMap<>();
            candidates.forEach(candidate -> results.put(
                candidate.merchantKey(), classification(candidate.merchantName())));
            return new ClassificationBatch(results, false, version, "model");
        });
        CachedClassificationService service = serviceWithCap(25);
        List<ClassificationCandidate> candidates = candidates(60);

        assertThat(service.classify(candidates).remainingMisses()).isEqualTo(35);
        assertThat(service.classify(candidates).remainingMisses()).isEqualTo(10);
        assertThat(service.classify(candidates).remainingMisses()).isZero();
        var cacheOnly = service.classify(candidates);

        assertThat(batchSizes).containsExactly(25, 25, 10);
        assertThat(cacheOnly.classifications()).hasSize(60);
        assertThat(cacheOnly.cacheHits()).isEqualTo(60);
        assertThat(cacheOnly.cacheWrites()).isZero();
    }

    @Test
    void changedFingerprintAndClassifierVersionAreCacheMisses() {
        configureCacheRepository();
        when(aiClient.metadata()).thenReturn(
            Optional.of(new ClassifierMetadata("version-one", "model-one", "v4")),
            Optional.of(new ClassifierMetadata("version-one", "model-one", "v4")),
            Optional.of(new ClassifierMetadata("version-two", "model-two", "v4")));
        when(aiClient.classify(anyList())).thenAnswer(invocation -> {
            List<ClassificationCandidate> candidates = invocation.getArgument(0);
            String version = stored.keySet().stream().anyMatch(key -> key.startsWith("version-one:"))
                ? "version-two" : "version-one";
            if (candidates.getFirst().merchantName().contains("renamed")) {
                version = "version-one";
            }
            Map<String, ModelClassification> results = Map.of(
                candidates.getFirst().merchantKey(), classification(candidates.getFirst().merchantName()));
            return new ClassificationBatch(results, false, version,
                version.equals("version-one") ? "model-one" : "model-two");
        });
        CachedClassificationService service = serviceWithCap(25);
        ClassificationCandidate original = candidates(1).getFirst();

        service.classify(List.of(original));
        var renamed = service.classify(List.of(new ClassificationCandidate(
            original.merchantKey(), "renamed merchant", null, null)));
        var newVersion = service.classify(List.of(original));

        assertThat(renamed.cacheWrites()).isEqualTo(1);
        assertThat(newVersion.cacheWrites()).isEqualTo(1);
        assertThat(stored.keySet()).contains(
            "version-one:merchant-00", "version-two:merchant-00");
    }

    @Test
    void unavailableMetadataSkipsCacheAndModelCalls() {
        when(aiClient.metadata()).thenReturn(Optional.empty());
        CachedClassificationService service = serviceWithCap(25);

        var resolution = service.classify(candidates(3));

        assertThat(resolution.classifications()).isEmpty();
        assertThat(resolution.remainingMisses()).isEqualTo(3);
        assertThat(resolution.degraded()).isTrue();
        verify(repository, never()).findAll(anyList(), anyString());
        verify(aiClient, never()).classify(anyList());
    }

    @Test
    void partialResponseCachesOnlyAnsweredCandidates() {
        configureCacheRepository();
        String version = "v4/c1/model";
        when(aiClient.metadata()).thenReturn(Optional.of(
            new ClassifierMetadata(version, "model", "v4")));
        when(aiClient.classify(anyList())).thenReturn(new ClassificationBatch(
            Map.of("merchant-00", classification("Merchant 0")), true, version, "model"));
        CachedClassificationService service = serviceWithCap(25);

        var resolution = service.classify(candidates(3));

        assertThat(resolution.classifications()).containsOnlyKeys("merchant-00");
        assertThat(resolution.cacheWrites()).isEqualTo(1);
        assertThat(resolution.remainingMisses()).isEqualTo(2);
        ArgumentCaptor<ClassificationCandidate> candidate =
            ArgumentCaptor.forClass(ClassificationCandidate.class);
        verify(repository).upsert(candidate.capture(), anyString(), anyString(), any(), anyString());
        assertThat(candidate.getValue().merchantKey()).isEqualTo("merchant-00");
    }

    @Test
    void versionChangeMidRunKeepsFreshResultsAndCostsOneModelCall() {
        configureCacheRepository();
        stored.put("version-one:merchant-00", new MerchantClassification(
            "merchant-00", "version-one", "fingerprint:Merchant 0",
            classification("Stale"), "model-one"));
        when(aiClient.metadata()).thenReturn(Optional.of(
            new ClassifierMetadata("version-one", "model-one", "v4")));
        when(aiClient.classify(anyList())).thenAnswer(invocation -> {
            List<ClassificationCandidate> candidates = invocation.getArgument(0);
            batchSizes.add(candidates.size());
            Map<String, ModelClassification> results = new LinkedHashMap<>();
            candidates.forEach(candidate -> results.put(
                candidate.merchantKey(), classification(candidate.merchantName())));
            return new ClassificationBatch(results, false, "version-two", "model-two");
        });
        CachedClassificationService service = serviceWithCap(25);

        var resolution = service.classify(candidates(3));

        assertThat(batchSizes).containsExactly(2);
        assertThat(resolution.cacheHits()).isZero();
        assertThat(resolution.classifications()).containsOnlyKeys("merchant-01", "merchant-02");
        assertThat(resolution.degraded()).isTrue();
        assertThat(stored.keySet()).contains("version-two:merchant-01", "version-two:merchant-02");
        assertThat(stored).doesNotContainKey("version-one:merchant-01");
    }

    private CachedClassificationService serviceWithCap(int cap) {
        return new CachedClassificationService(
            aiClient, repository, fingerprint, new DetectionProperties(cap));
    }

    private List<ClassificationCandidate> candidates(int count) {
        return java.util.stream.IntStream.range(0, count)
            .mapToObj(index -> new ClassificationCandidate(
                "merchant-%02d".formatted(index), "Merchant " + index, null, null))
            .toList();
    }

    private ModelClassification classification(String displayName) {
        return new ModelClassification(
            displayName, RecurringCategory.OTHER, 0.80, true, "Model result", "v4");
    }
}
