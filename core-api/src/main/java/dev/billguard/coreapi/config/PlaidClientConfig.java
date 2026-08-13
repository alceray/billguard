package dev.billguard.coreapi.config;

import java.util.Map;

import com.plaid.client.ApiClient;
import com.plaid.client.request.PlaidApi;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PlaidClientConfig {
    @Bean
    PlaidApi plaidApi(PlaidProperties properties) {
        ApiClient client = new ApiClient(Map.of(
            "clientId", properties.clientId(),
            "secret", properties.secret()
        ));
        client.setPlaidAdapter(resolveBaseUrl(properties));
        client.setTimeout(15);
        return client.createService(PlaidApi.class);
    }

    private String resolveBaseUrl(PlaidProperties properties) {
        if (!properties.baseUrl().isBlank()) {
            return properties.baseUrl();
        }
        return switch (properties.env().toLowerCase(java.util.Locale.ROOT)) {
            case "sandbox" -> ApiClient.Sandbox;
            case "production" -> ApiClient.Production;
            default -> throw new IllegalStateException(
                "PLAID_ENV must be either 'sandbox' or 'production'");
        };
    }
}
