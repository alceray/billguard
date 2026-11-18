package dev.billguard.coreapi.detection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.billguard.coreapi.config.AiProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class AiClassificationClient {
    private static final Logger log = LoggerFactory.getLogger(AiClassificationClient.class);

    private final RestClient restClient;

    @Autowired
    public AiClassificationClient(AiProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());
        this.restClient = RestClient.builder()
            .baseUrl(properties.serviceUrl())
            .requestFactory(requestFactory)
            .build();
    }

    AiClassificationClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public Optional<ClassifierMetadata> metadata() {
        try {
            MetadataResponse response = restClient.get()
                .uri("/internal/classify/metadata")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(MetadataResponse.class);
            if (response == null || isBlank(response.classifierVersion())
                || isBlank(response.model()) || isBlank(response.promptVersion())) {
                return Optional.empty();
            }
            return Optional.of(new ClassifierMetadata(
                response.classifierVersion(), response.model(), response.promptVersion()));
        } catch (RestClientException exception) {
            log.warn("AI classification metadata unavailable; continuing with deterministic rules error={}",
                exception.getMessage());
            return Optional.empty();
        }
    }

    public ClassificationBatch classify(List<ClassificationCandidate> candidates) {
        if (candidates.isEmpty()) {
            return ClassificationBatch.unavailable();
        }
        try {
            ClassificationResponse response = restClient.post()
                .uri("/internal/classify")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(new ClassificationRequest(candidates))
                .retrieve()
                .body(ClassificationResponse.class);
            if (response == null || response.results() == null
                || isBlank(response.classifierVersion()) || isBlank(response.model())
                || isBlank(response.promptVersion())) {
                return ClassificationBatch.unavailable();
            }
            Map<String, ModelClassification> classifications = new LinkedHashMap<>();
            for (ClassificationResult result : response.results()) {
                if (!valid(result)) {
                    continue;
                }
                classifications.put(result.merchantKey(), new ModelClassification(
                    result.displayName(), RecurringCategory.fromValue(result.category()),
                    result.confidence(), result.recurringSignal(), result.reasoning(),
                    response.promptVersion()));
            }
            return new ClassificationBatch(
                Map.copyOf(classifications),
                response.degraded() || classifications.size() != response.results().size(),
                response.classifierVersion(), response.model());
        } catch (RestClientException exception) {
            log.warn("AI classification unavailable; continuing with deterministic rules error={}",
                exception.getMessage());
            return ClassificationBatch.unavailable();
        }
    }

    private boolean valid(ClassificationResult result) {
        if (result == null || isBlank(result.merchantKey()) || isBlank(result.displayName())
            || isBlank(result.category()) || result.confidence() < 0 || result.confidence() > 1) {
            return false;
        }
        try {
            RecurringCategory.fromJson(result.category());
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record ClassifierMetadata(
        String classifierVersion,
        String model,
        String promptVersion
    ) {
    }

    public record ClassificationBatch(
        Map<String, ModelClassification> results,
        boolean degraded,
        String classifierVersion,
        String model
    ) {
        static ClassificationBatch unavailable() {
            return new ClassificationBatch(Map.of(), true, null, null);
        }
    }

    private record ClassificationRequest(List<ClassificationCandidate> candidates) {
    }

    private record MetadataResponse(
        @JsonProperty("classifier_version") String classifierVersion,
        String model,
        @JsonProperty("prompt_version") String promptVersion
    ) {
    }

    private record ClassificationResponse(
        List<ClassificationResult> results,
        String model,
        @JsonProperty("prompt_version") String promptVersion,
        @JsonProperty("classifier_version") String classifierVersion,
        boolean degraded
    ) {
    }

    private record ClassificationResult(
        @JsonProperty("merchant_key") String merchantKey,
        @JsonProperty("display_name") String displayName,
        String category,
        double confidence,
        @JsonProperty("recurring_signal") boolean recurringSignal,
        String reasoning
    ) {
    }
}
