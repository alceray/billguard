package dev.billguard.coreapi.detection;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.billguard.coreapi.config.AiProperties;

import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class AiClassificationClientTest {
    @Test
    void postsJsonContractAndMapsClassificationResponse() {
        WireMockServer aiService = new WireMockServer(options().dynamicPort());
        aiService.start();
        try {
            aiService.stubFor(post(urlEqualTo("/internal/classify"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(matchingJsonPath("$.streams[0].merchant_key", equalTo("netflix")))
                .withRequestBody(matchingJsonPath("$.streams[0].transaction_count", equalTo("3")))
                .willReturn(aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("""
                        {
                          "results": [{
                            "merchant_key": "netflix",
                            "display_name": "Netflix",
                            "category": "streaming",
                            "confidence": 0.95,
                            "recurring_signal": true,
                            "reasoning": "Known streaming service"
                          }],
                          "model": "test-model",
                          "prompt_version": "v1",
                          "degraded": false
                        }
                        """)));

            AiClassificationClient client = new AiClassificationClient(
                new AiProperties(aiService.baseUrl(), Duration.ofSeconds(2)));
            List<DetectionTransaction> transactions = List.of(
                transaction("netflix", LocalDate.of(2026, 6, 1)),
                transaction("netflix", LocalDate.of(2026, 7, 1)),
                transaction("netflix", LocalDate.of(2026, 8, 1)));

            Map<String, ModelClassification> results = client.classify(
                List.of(new ChargeGroup("netflix", transactions)));

            assertThat(results).containsKey("netflix");
            assertThat(results.get("netflix").category()).isEqualTo(RecurringCategory.STREAMING);
            assertThat(results.get("netflix").promptVersion()).isEqualTo("v1");
        } finally {
            aiService.stop();
        }
    }

    @Test
    void unavailableServiceReturnsNoClassificationsInsteadOfFailingTheJob() {
        AiClassificationClient client = new AiClassificationClient(
            new AiProperties("http://127.0.0.1:1", Duration.ofMillis(100)));
        DetectionTransaction transaction = new DetectionTransaction(
            UUID.randomUUID(), UUID.randomUUID(), "netflix", "Netflix", "Netflix",
            new BigDecimal("15.49"), "USD", LocalDate.of(2026, 8, 1),
            false, "ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES", false);

        assertThat(client.classify(List.of(new ChargeGroup("netflix", List.of(transaction)))))
            .isEmpty();
    }

    private DetectionTransaction transaction(String merchantKey, LocalDate date) {
        return new DetectionTransaction(
            UUID.randomUUID(), UUID.randomUUID(), merchantKey, "Netflix", "Netflix",
            new BigDecimal("15.49"), "USD", date,
            false, "ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES", false);
    }
}
