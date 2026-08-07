package dev.billguard.coreapi.auth;

import java.time.Instant;
import java.util.UUID;

public interface UserProjection {
    UUID getId();
    String getEmail();
    String getName();
    Instant getCreatedAt();
}
