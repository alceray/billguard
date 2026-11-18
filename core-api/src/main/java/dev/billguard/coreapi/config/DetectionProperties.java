package dev.billguard.coreapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("billguard.detection")
public record DetectionProperties(int maxClassificationsPerRun) {
    private static final int DEFAULT_MAX_CLASSIFICATIONS_PER_RUN = 25;

    public DetectionProperties {
        if (maxClassificationsPerRun <= 0) {
            maxClassificationsPerRun = DEFAULT_MAX_CLASSIFICATIONS_PER_RUN;
        }
    }
}
