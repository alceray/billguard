package dev.billguard.coreapi.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("billguard.ai")
public record AiProperties(String serviceUrl, Duration timeout) {
    public AiProperties {
        serviceUrl = serviceUrl == null || serviceUrl.isBlank() ? "http://localhost:8000" : serviceUrl;
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
    }
}
