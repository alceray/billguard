package dev.billguard.coreapi.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("billguard.worker")
public record WorkerProperties(
    boolean enabled,
    long pollDelay,
    int batchSize,
    Duration staleAfter
) {
    public WorkerProperties {
        pollDelay = pollDelay > 0 ? pollDelay : 2_000;
        batchSize = batchSize > 0 ? batchSize : 10;
        staleAfter = staleAfter == null ? Duration.ofMinutes(10) : staleAfter;
    }
}
