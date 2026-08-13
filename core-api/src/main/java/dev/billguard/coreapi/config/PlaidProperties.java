package dev.billguard.coreapi.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("billguard.plaid")
public record PlaidProperties(
    String env,
    String clientId,
    String secret,
    String webhookUrl,
    List<String> products,
    List<String> countryCodes,
    String baseUrl
) {
    public PlaidProperties {
        env = env == null || env.isBlank() ? "sandbox" : env;
        clientId = clientId == null ? "" : clientId;
        secret = secret == null ? "" : secret;
        webhookUrl = webhookUrl == null ? "" : webhookUrl;
        products = products == null || products.isEmpty() ? List.of("transactions") : List.copyOf(products);
        countryCodes = countryCodes == null || countryCodes.isEmpty() ? List.of("US") : List.copyOf(countryCodes);
        baseUrl = baseUrl == null ? "" : baseUrl;
    }
}
