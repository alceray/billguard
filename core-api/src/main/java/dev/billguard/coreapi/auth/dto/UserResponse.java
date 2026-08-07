package dev.billguard.coreapi.auth.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.billguard.coreapi.auth.User;
import dev.billguard.coreapi.auth.UserProjection;

public record UserResponse(
    UUID id,
    String email,
    String name,
    @JsonProperty("created_at") Instant createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getName(), user.getCreatedAt());
    }

    public static UserResponse from(UserProjection user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getName(), user.getCreatedAt());
    }
}
