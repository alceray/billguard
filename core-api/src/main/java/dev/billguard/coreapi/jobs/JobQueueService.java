package dev.billguard.coreapi.jobs;

import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

@Service
public class JobQueueService {
    public static final String SYNC_ITEM = "sync_item";

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
}
