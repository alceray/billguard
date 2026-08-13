package dev.billguard.coreapi.jobs;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JobQueueRepository {
    private final JdbcTemplate jdbcTemplate;

    public JobQueueRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean enqueue(String type, String dedupeKey, String payload) {
        return jdbcTemplate.update("""
            INSERT INTO job_queue (type, dedupe_key, payload)
            VALUES (?, ?, CAST(? AS jsonb))
            ON CONFLICT (type, dedupe_key)
              WHERE status IN ('pending', 'running')
            DO NOTHING
            """, type, dedupeKey, payload) == 1;
    }

    @Transactional
    public List<JobQueueEntry> claim(int batchSize) {
        jdbcTemplate.update("""
            UPDATE job_queue
               SET status = 'dead', last_error = COALESCE(last_error, 'Maximum attempts reached'), updated_at = NOW()
             WHERE status = 'pending' AND attempts >= 5
            """);
        return jdbcTemplate.query("""
            UPDATE job_queue
               SET status = 'running', attempts = attempts + 1, updated_at = NOW()
             WHERE id IN (
               SELECT id
                 FROM job_queue
                WHERE status = 'pending' AND attempts < 5 AND run_after <= NOW()
                ORDER BY run_after, created_at
                FOR UPDATE SKIP LOCKED
                LIMIT ?
             )
            RETURNING id, type, dedupe_key, payload::text, attempts
            """, JobQueueRepository::mapJob, batchSize);
    }

    public void markDone(UUID id) {
        jdbcTemplate.update("""
            UPDATE job_queue
               SET status = 'done', last_error = NULL, updated_at = NOW()
             WHERE id = ? AND status = 'running'
            """, id);
    }

    public void retry(UUID id, Duration delay, String error) {
        jdbcTemplate.update("""
            UPDATE job_queue
               SET status = 'pending', run_after = NOW() + (? * INTERVAL '1 millisecond'),
                   last_error = ?, updated_at = NOW()
             WHERE id = ? AND status = 'running'
            """, delay.toMillis(), truncate(error), id);
    }

    public void markDead(UUID id, String error) {
        jdbcTemplate.update("""
            UPDATE job_queue
               SET status = 'dead', last_error = ?, updated_at = NOW()
             WHERE id = ? AND status = 'running'
            """, truncate(error), id);
    }

    public int reclaimStale(Duration staleAfter) {
        return jdbcTemplate.update("""
            UPDATE job_queue
               SET status = CASE WHEN attempts >= 5 THEN 'dead' ELSE 'pending' END,
                   run_after = NOW(), last_error = 'Worker stopped before completion', updated_at = NOW()
             WHERE status = 'running'
               AND updated_at < NOW() - (? * INTERVAL '1 millisecond')
            """, staleAfter.toMillis());
    }

    private static JobQueueEntry mapJob(ResultSet resultSet, int rowNumber) throws SQLException {
        return new JobQueueEntry(
            resultSet.getObject("id", UUID.class),
            resultSet.getString("type"),
            resultSet.getString("dedupe_key"),
            resultSet.getString("payload"),
            resultSet.getInt("attempts")
        );
    }

    private static String truncate(String value) {
        if (value == null) {
            return "Unknown worker error";
        }
        return value.length() <= 1_000 ? value : value.substring(0, 1_000);
    }
}
