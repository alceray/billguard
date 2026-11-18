package dev.billguard.coreapi.detection;

import java.util.List;

import dev.billguard.coreapi.detection.AiClassificationClient.ClassificationBatch;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiClassificationClientTest {
    @Test
    void readsMetadataAndPostsSlimCandidateContract() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai-service");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AiClassificationClient client = new AiClassificationClient(builder.build());
        server.expect(once(), requestTo("http://ai-service/internal/classify/metadata"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""
                {
                  "classifier_version": "v4/c1/test-model",
                  "model": "test-model",
                  "prompt_version": "v4"
                }
                """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://ai-service/internal/classify"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""
                {
                  "candidates": [{
                    "merchant_key": "netflix",
                    "merchant_name": "NETFLIX.COM",
                    "pfc_primary": "ENTERTAINMENT",
                    "pfc_detailed": "ENTERTAINMENT_TV_AND_MOVIES"
                  }]
                }
                """, true))
            .andRespond(withSuccess("""
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
                  "prompt_version": "v4",
                  "classifier_version": "v4/c1/test-model",
                  "degraded": false
                }
                """, MediaType.APPLICATION_JSON));
        ClassificationCandidate candidate = new ClassificationCandidate(
            "netflix", "NETFLIX.COM", "ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES");

        assertThat(client.metadata()).get().satisfies(metadata -> {
            assertThat(metadata.classifierVersion()).isEqualTo("v4/c1/test-model");
            assertThat(metadata.model()).isEqualTo("test-model");
        });
        ClassificationBatch batch = client.classify(List.of(candidate));

        assertThat(batch.results()).containsKey("netflix");
        assertThat(batch.results().get("netflix").category())
            .isEqualTo(RecurringCategory.STREAMING);
        assertThat(batch.results().get("netflix").promptVersion()).isEqualTo("v4");
        assertThat(batch.classifierVersion()).isEqualTo("v4/c1/test-model");
        server.verify();
    }

    @Test
    void unavailableServiceReturnsNoMetadataOrClassifications() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai-service");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AiClassificationClient client = new AiClassificationClient(builder.build());
        server.expect(requestTo("http://ai-service/internal/classify/metadata"))
            .andRespond(withServerError());
        server.expect(requestTo("http://ai-service/internal/classify"))
            .andRespond(withServerError());
        ClassificationCandidate candidate = new ClassificationCandidate(
            "netflix", "Netflix", null, null);

        assertThat(client.metadata()).isEmpty();
        ClassificationBatch batch = client.classify(List.of(candidate));

        assertThat(batch.results()).isEmpty();
        assertThat(batch.classifierVersion()).isNull();
        assertThat(batch.degraded()).isTrue();
        server.verify();
    }

    @Test
    void malformedModelResultIsIgnoredAndMarksTheBatchDegraded() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai-service");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AiClassificationClient client = new AiClassificationClient(builder.build());
        server.expect(requestTo("http://ai-service/internal/classify"))
            .andRespond(withSuccess("""
                {
                  "results": [{
                    "merchant_key": "netflix",
                    "display_name": "Netflix",
                    "category": "not-a-category",
                    "confidence": 0.95,
                    "recurring_signal": true,
                    "reasoning": "Malformed result"
                  }],
                  "model": "test-model",
                  "prompt_version": "v4",
                  "classifier_version": "v4/c1/test-model",
                  "degraded": false
                }
                """, MediaType.APPLICATION_JSON));

        ClassificationBatch batch = client.classify(List.of(
            new ClassificationCandidate("netflix", "Netflix", null, null)));

        assertThat(batch.results()).isEmpty();
        assertThat(batch.degraded()).isTrue();
        server.verify();
    }
}
