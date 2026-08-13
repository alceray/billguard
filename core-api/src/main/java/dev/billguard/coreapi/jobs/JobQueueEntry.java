package dev.billguard.coreapi.jobs;

import java.util.UUID;

public record JobQueueEntry(
    UUID id,
    String type,
    String dedupeKey,
    String payload,
    int attempts
) {
}
