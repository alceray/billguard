package dev.billguard.coreapi.detection;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "AUTH0_DOMAIN=test.auth0.com",
    "AUTH0_AUDIENCE=https://api.billguard.test",
    "DATABASE_PASSWORD=test",
    "TOKEN_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class MerchantClassificationRepositoryIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MerchantClassificationRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("TRUNCATE merchant_classifications");
    }

    @Test
    void upsertReadsBackAndUpdatesTheSameVersion() {
        ClassificationCandidate candidate = new ClassificationCandidate(
            "netflix", "NETFLIX.COM", "ENTERTAINMENT", "ENTERTAINMENT_TV_AND_MOVIES");
        repository.upsert(candidate, "v4/c1/model", "fingerprint-one",
            classification("Netflix", RecurringCategory.STREAMING), "model");

        MerchantClassification initial = repository.findAll(
            List.of("netflix"), "v4/c1/model").get("netflix");
        assertThat(initial.fingerprint()).isEqualTo("fingerprint-one");
        assertThat(initial.classification().displayName()).isEqualTo("Netflix");

        repository.upsert(candidate, "v4/c1/model", "fingerprint-two",
            classification("Netflix Canada", RecurringCategory.STREAMING), "model");
        MerchantClassification updated = repository.findAll(
            List.of("netflix"), "v4/c1/model").get("netflix");

        assertThat(updated.fingerprint()).isEqualTo("fingerprint-two");
        assertThat(updated.classification().displayName()).isEqualTo("Netflix Canada");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM merchant_classifications", Integer.class)).isEqualTo(1);
    }

    @Test
    void classifierVersionsCoexistAndLookupDoesNotCrossVersions() {
        ClassificationCandidate candidate = new ClassificationCandidate(
            "netflix", "NETFLIX.COM", null, null);
        repository.upsert(candidate, "version-one", "fingerprint",
            classification("Netflix", RecurringCategory.STREAMING), "model-one");

        assertThat(repository.findAll(List.of("netflix"), "version-two")).isEmpty();

        repository.upsert(candidate, "version-two", "fingerprint",
            classification("Netflix", RecurringCategory.STREAMING), "model-two");
        assertThat(repository.findAll(List.of("netflix"), "version-one")).containsKey("netflix");
        assertThat(repository.findAll(List.of("netflix"), "version-two")).containsKey("netflix");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM merchant_classifications", Integer.class)).isEqualTo(2);
    }

    private ModelClassification classification(String displayName, RecurringCategory category) {
        return new ModelClassification(
            displayName, category, 0.95, true, "Known merchant", "v4");
    }
}
