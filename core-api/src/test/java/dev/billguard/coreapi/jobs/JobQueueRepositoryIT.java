package dev.billguard.coreapi.jobs;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import dev.billguard.coreapi.plaid.PlaidWebhookPersistenceService;

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
class JobQueueRepositoryIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JobQueueRepository repository;

    @Autowired
    private PlaidWebhookPersistenceService webhookPersistenceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("TRUNCATE job_queue, webhook_events CASCADE");
    }

    @Test
    void duplicateEnqueueProducesOneInflightJob() {
        assertThat(repository.enqueue("sync_item", "item-one", "{}" )).isTrue();
        assertThat(repository.enqueue("sync_item", "item-one", "{}" )).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM job_queue", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentClaimsReturnDisjointJobs() throws Exception {
        for (int index = 0; index < 20; index++) {
            repository.enqueue("sync_item", "item-" + index, "{}");
        }
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<List<JobQueueEntry>> first = executor.submit(() -> claimTogether(ready, start));
            Future<List<JobQueueEntry>> second = executor.submit(() -> claimTogether(ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<JobQueueEntry> firstJobs = first.get(10, TimeUnit.SECONDS);
            List<JobQueueEntry> secondJobs = second.get(10, TimeUnit.SECONDS);

            Set<UUID> ids = new HashSet<>();
            firstJobs.forEach(job -> ids.add(job.id()));
            int firstCount = ids.size();
            secondJobs.forEach(job -> ids.add(job.id()));

            assertThat(firstJobs).hasSize(10);
            assertThat(secondJobs).hasSize(10);
            assertThat(firstCount).isEqualTo(10);
            assertThat(ids).hasSize(20);
        }
    }

    @Test
    void jobsAtFiveAttemptsAreMovedToDeadInsteadOfClaimed() {
        repository.enqueue("sync_item", "item-dead", "{}");
        jdbcTemplate.update("UPDATE job_queue SET attempts = 5 WHERE dedupe_key = 'item-dead'");

        assertThat(repository.claim(10)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM job_queue WHERE dedupe_key = 'item-dead'", String.class)).isEqualTo("dead");
    }

    @Test
    void replayedWebhookCreatesOneEventAndOneJob() {
        String body = "{\"webhook_type\":\"TRANSACTIONS\",\"webhook_code\":\"SYNC_UPDATES_AVAILABLE\"}";
        webhookPersistenceService.persist("same-hash", "TRANSACTIONS", "SYNC_UPDATES_AVAILABLE", "item", null, body);
        webhookPersistenceService.persist("same-hash", "TRANSACTIONS", "SYNC_UPDATES_AVAILABLE", "item", null, body);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM webhook_events", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM job_queue", Integer.class)).isEqualTo(1);
    }

    private List<JobQueueEntry> claimTogether(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await(5, TimeUnit.SECONDS);
        return repository.claim(10);
    }
}
