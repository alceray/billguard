package dev.billguard.coreapi.plaid;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import dev.billguard.coreapi.common.HttpException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PlaidWebhookService {
    private final PlaidWebhookVerifier verifier;
    private final PlaidWebhookPersistenceService persistenceService;
    private final ObjectMapper objectMapper;

    public PlaidWebhookService(
        PlaidWebhookVerifier verifier,
        PlaidWebhookPersistenceService persistenceService,
        ObjectMapper objectMapper
    ) {
        this.verifier = verifier;
        this.persistenceService = persistenceService;
        this.objectMapper = objectMapper;
    }

    public void handle(String verificationHeader, String rawBody) {
        verifier.verify(verificationHeader, rawBody);
        try {
            JsonNode body = objectMapper.readTree(rawBody);
            String webhookType = requiredText(body, "webhook_type");
            String webhookCode = requiredText(body, "webhook_code");
            String itemId = optionalText(body, "item_id");
            String errorCode = body.path("error").isMissingNode()
                ? null
                : optionalText(body.path("error"), "error_code");
            persistenceService.persist(
                sha256(rawBody), webhookType, webhookCode, itemId, errorCode, rawBody);
        } catch (JacksonException exception) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Invalid Plaid webhook body");
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Invalid Plaid webhook body");
        }
        return value;
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
