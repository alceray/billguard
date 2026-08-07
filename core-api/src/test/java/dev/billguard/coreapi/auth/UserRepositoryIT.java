package dev.billguard.coreapi.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "AUTH0_DOMAIN=test.auth0.com",
    "AUTH0_AUDIENCE=https://api.billguard.test",
    "DATABASE_PASSWORD=test"
})
class UserRepositoryIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private UserRepository userRepository;

    @Test
    @Transactional
    void upsertUpdatesEmailAndPreservesExistingNameWhenNameIsNull() {
        UserProjection first = userRepository.upsert("auth0|same", "old@example.com", "Ray");
        UserProjection second = userRepository.upsert("auth0|same", "new@example.com", null);

        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getEmail()).isEqualTo("new@example.com");
        assertThat(second.getName()).isEqualTo("Ray");
    }
}
