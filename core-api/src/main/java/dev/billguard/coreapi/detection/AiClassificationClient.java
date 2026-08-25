package dev.billguard.coreapi.detection;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.billguard.coreapi.config.AiProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class AiClassificationClient {
    private static final Logger log = LoggerFactory.getLogger(AiClassificationClient.class);

    private final RestClient restClient;

    public AiClassificationClient(AiProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.timeout())
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout());
        this.restClient = RestClient.builder()
            .baseUrl(properties.serviceUrl())
            .requestFactory(requestFactory)
            .build();
    }

    public Map<String, ModelClassification> classify(List<ChargeGroup> groups) {
        if (groups.isEmpty()) {
            return Map.of();
        }
        List<ClassificationStream> streams = groups.stream()
            .filter(group -> group.transactions().size() >= 3)
            .map(ClassificationStream::from)
            .toList();
        List<ClassificationStream> singletons = groups.stream()
            .filter(group -> group.transactions().size() <= 2)
            .map(ClassificationStream::from)
            .toList();
        try {
            ClassificationResponse response = restClient.post()
                .uri("/internal/classify")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(new ClassificationRequest(streams, singletons))
                .retrieve()
                .body(ClassificationResponse.class);
            if (response == null || response.results() == null) {
                return Map.of();
            }
            Map<String, ModelClassification> classifications = new LinkedHashMap<>();
            for (ClassificationResult result : response.results()) {
                classifications.put(result.merchantKey(), new ModelClassification(
                    result.displayName(), RecurringCategory.fromValue(result.category()),
                    result.confidence(), result.recurringSignal(), result.reasoning(), response.promptVersion()));
            }
            return classifications;
        } catch (RestClientException exception) {
            log.warn("AI classification unavailable; continuing with deterministic rules error={}",
                exception.getMessage());
            return Map.of();
        }
    }

    public record ClassificationRequest(
        List<ClassificationStream> streams,
        List<ClassificationStream> singletons
    ) {
    }

    public record ClassificationStream(
        @JsonProperty("merchant_key") String merchantKey,
        @JsonProperty("merchant_name") String merchantName,
        @JsonProperty("transaction_count") int transactionCount,
        List<BigDecimal> amounts,
        List<LocalDate> dates,
        String currency,
        @JsonProperty("pfc_primary") String pfcPrimary,
        @JsonProperty("pfc_detailed") String pfcDetailed
    ) {
        static ClassificationStream from(ChargeGroup group) {
            DetectionTransaction latest = group.latest();
            return new ClassificationStream(
                group.merchantKey(), latest.merchantLabel(), group.transactions().size(),
                group.transactions().stream().map(DetectionTransaction::amount).toList(),
                group.transactions().stream().map(DetectionTransaction::date).toList(),
                latest.currency(), latest.pfcPrimary(), latest.pfcDetailed());
        }
    }

    public record ClassificationResponse(
        List<ClassificationResult> results,
        String model,
        @JsonProperty("prompt_version") String promptVersion,
        boolean degraded
    ) {
    }

    public record ClassificationResult(
        @JsonProperty("merchant_key") String merchantKey,
        @JsonProperty("display_name") String displayName,
        String category,
        double confidence,
        @JsonProperty("recurring_signal") boolean recurringSignal,
        String reasoning
    ) {
    }
}
