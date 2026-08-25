package dev.billguard.coreapi.jobs;

import java.util.Map;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

@Service
public class JobQueueService {
    public static final String SYNC_ITEM = "sync_item";
    public static final String DETECT_STREAMS = "detect_streams";

    private final JobQueueRepository repository;
    private final ObjectMapper objectMapper;

    public JobQueueService(JobQueueRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public boolean enqueue(String type, String dedupeKey, Map<String, Object> payload) {
        try {
            return repository.enqueue(type, dedupeKey, objectMapper.writeValueAsString(payload));
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Job payload is not serializable", exception);
        }
    }

    public boolean enqueueSync(String plaidItemId) {
        return enqueue(SYNC_ITEM, plaidItemId, Map.of("plaid_item_id", plaidItemId));
    }

    public boolean enqueueDetection(UUID userId) {
        return enqueue(DETECT_STREAMS, userId.toString(), Map.of("user_id", userId.toString()));
    }

    public void deleteSyncJobs(String plaidItemId) {
        repository.delete(SYNC_ITEM, plaidItemId);
    }

    public String detectionStatus(UUID userId) {
        return repository.latestStatus(DETECT_STREAMS, userId.toString())
            .map(status -> status.equals("dead") ? "failed" : status)
            .orElse("idle");
    }
}
