package dev.billguard.coreapi.detection;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MerchantClassificationRepository {
    private final JdbcTemplate jdbcTemplate;

    public MerchantClassificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, MerchantClassification> findAll(
        Collection<String> merchantKeys,
        String classifierVersion
    ) {
        if (merchantKeys.isEmpty()) {
            return Map.of();
        }
        Map<String, MerchantClassification> classifications = new LinkedHashMap<>();
        jdbcTemplate.query(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                SELECT merchant_key, classifier_version, fingerprint, display_name, category,
                       confidence, recurring_signal, reasoning, model, prompt_version
                  FROM merchant_classifications
                 WHERE classifier_version = ? AND merchant_key = ANY (?)
                """);
            statement.setString(1, classifierVersion);
            Array keys = connection.createArrayOf("text", merchantKeys.toArray(String[]::new));
            statement.setArray(2, keys);
            return statement;
        }, resultSet -> {
            MerchantClassification classification = mapClassification(resultSet);
            classifications.put(classification.merchantKey(), classification);
        });
        return Map.copyOf(classifications);
    }

    public void upsert(
        ClassificationCandidate candidate,
        String classifierVersion,
        String fingerprint,
        ModelClassification classification,
        String model
    ) {
        jdbcTemplate.update("""
            INSERT INTO merchant_classifications (
              merchant_key, classifier_version, fingerprint, display_name, category,
              confidence, recurring_signal, reasoning, model, prompt_version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (merchant_key, classifier_version) DO UPDATE
               SET fingerprint = EXCLUDED.fingerprint,
                   display_name = EXCLUDED.display_name,
                   category = EXCLUDED.category,
                   confidence = EXCLUDED.confidence,
                   recurring_signal = EXCLUDED.recurring_signal,
                   reasoning = EXCLUDED.reasoning,
                   model = EXCLUDED.model,
                   prompt_version = EXCLUDED.prompt_version,
                   updated_at = NOW()
            """,
            candidate.merchantKey(), classifierVersion, fingerprint,
            classification.displayName(), classification.category().value(),
            BigDecimal.valueOf(classification.confidence()), classification.recurringSignal(),
            classification.reasoning(), model, classification.promptVersion());
    }

    private static MerchantClassification mapClassification(ResultSet resultSet)
        throws SQLException {
        ModelClassification classification = new ModelClassification(
            resultSet.getString("display_name"),
            RecurringCategory.fromValue(resultSet.getString("category")),
            resultSet.getBigDecimal("confidence").doubleValue(),
            resultSet.getBoolean("recurring_signal"),
            resultSet.getString("reasoning"),
            resultSet.getString("prompt_version"));
        return new MerchantClassification(
            resultSet.getString("merchant_key"),
            resultSet.getString("classifier_version"),
            resultSet.getString("fingerprint"),
            classification,
            resultSet.getString("model"));
    }
}
