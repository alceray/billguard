package dev.billguard.coreapi.detection;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RecurringChargeDetectionRepository {
    private final JdbcTemplate jdbcTemplate;

    public RecurringChargeDetectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<DetectionTransaction> findTransactions(UUID userId) {
        return jdbcTemplate.query("""
            SELECT transaction.id, transaction.account_id, transaction.merchant_key,
                   transaction.merchant_name, transaction.name, transaction.amount,
                   transaction.currency, transaction.date, transaction.pending,
                   transaction.pfc_primary, transaction.pfc_detailed,
                   EXISTS (
                     SELECT 1
                       FROM transactions counterpart
                       JOIN accounts counterpart_account ON counterpart_account.id = counterpart.account_id
                      WHERE counterpart_account.user_id = account.user_id
                        AND counterpart.account_id <> transaction.account_id
                        AND counterpart.pending = FALSE
                        AND ABS(counterpart.amount + transaction.amount) <= 0.01
                        AND ABS(counterpart.date - transaction.date) <= 3
                        AND (counterpart.pfc_primary LIKE 'TRANSFER%'
                          OR counterpart.pfc_detailed LIKE 'TRANSFER%')
                   ) AS matched_own_transfer
              FROM transactions transaction
              JOIN accounts account ON account.id = transaction.account_id
             WHERE account.user_id = ?
             ORDER BY transaction.date, transaction.id
            """, RecurringChargeDetectionRepository::mapTransaction, userId);
    }

    public void upsert(UUID userId, DetectedCharge charge) {
        jdbcTemplate.update("""
            INSERT INTO recurring_charges (
              user_id, merchant_key, merchant_name, display_name, amount, previous_amount,
              currency, billing_cycle, next_charge_date, confidence_score, status,
              detection_state, category, category_source, amount_changed_at,
              is_variable_amount, source_transaction_id, first_seen_at, last_seen_at,
              classification_prompt_version, classification_reasoning
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id, merchant_key) DO UPDATE
               SET merchant_name = CASE WHEN recurring_charges.user_override IS NULL
                     THEN EXCLUDED.merchant_name ELSE recurring_charges.merchant_name END,
                   display_name = CASE WHEN recurring_charges.user_override IS NULL
                     THEN EXCLUDED.display_name ELSE recurring_charges.display_name END,
                   amount = CASE WHEN recurring_charges.user_override IS NULL
                     THEN EXCLUDED.amount ELSE recurring_charges.amount END,
                   previous_amount = CASE WHEN recurring_charges.user_override IS NULL
                     THEN EXCLUDED.previous_amount ELSE recurring_charges.previous_amount END,
                   currency = CASE WHEN recurring_charges.user_override IS NULL
                     THEN EXCLUDED.currency ELSE recurring_charges.currency END,
                   billing_cycle = CASE WHEN recurring_charges.user_override IS NULL
                     THEN EXCLUDED.billing_cycle ELSE recurring_charges.billing_cycle END,
                   next_charge_date = CASE WHEN recurring_charges.user_override IS NULL
                     THEN EXCLUDED.next_charge_date ELSE recurring_charges.next_charge_date END,
                   confidence_score = CASE WHEN recurring_charges.user_override IS NULL
                     THEN EXCLUDED.confidence_score ELSE recurring_charges.confidence_score END,
                   status = CASE WHEN recurring_charges.user_override IS NULL THEN 'active'
                     ELSE recurring_charges.status END,
                   detection_state = CASE WHEN recurring_charges.user_override IS NULL
                     THEN EXCLUDED.detection_state ELSE recurring_charges.detection_state END,
                   category = CASE
                     WHEN recurring_charges.category_source = 'user' THEN recurring_charges.category
                     WHEN recurring_charges.user_override IS NULL THEN EXCLUDED.category
                     ELSE recurring_charges.category
                   END,
                   category_source = CASE
                     WHEN recurring_charges.category_source = 'user' THEN recurring_charges.category_source
                     WHEN recurring_charges.user_override IS NULL THEN EXCLUDED.category_source
                     ELSE recurring_charges.category_source
                   END,
                   amount_changed_at = CASE WHEN recurring_charges.user_override IS NULL
                     THEN EXCLUDED.amount_changed_at ELSE recurring_charges.amount_changed_at END,
                   is_variable_amount = CASE WHEN recurring_charges.user_override IS NULL
                     THEN EXCLUDED.is_variable_amount ELSE recurring_charges.is_variable_amount END,
                   source_transaction_id = CASE WHEN recurring_charges.user_override IS NULL
                     THEN EXCLUDED.source_transaction_id ELSE recurring_charges.source_transaction_id END,
                   first_seen_at = CASE WHEN recurring_charges.user_override IS NULL
                     THEN LEAST(recurring_charges.first_seen_at, EXCLUDED.first_seen_at)
                     ELSE recurring_charges.first_seen_at END,
                   last_seen_at = CASE WHEN recurring_charges.user_override IS NULL
                     THEN EXCLUDED.last_seen_at ELSE recurring_charges.last_seen_at END,
                   classification_prompt_version = CASE WHEN recurring_charges.user_override IS NULL
                     THEN EXCLUDED.classification_prompt_version
                     ELSE recurring_charges.classification_prompt_version END,
                   classification_reasoning = CASE WHEN recurring_charges.user_override IS NULL
                     THEN EXCLUDED.classification_reasoning ELSE recurring_charges.classification_reasoning END,
                   updated_at = NOW()
            """,
            userId, charge.merchantKey(), charge.merchantName(), charge.displayName(), charge.amount(),
            charge.previousAmount(), charge.currency(),
            charge.billingCycle() == null ? null : charge.billingCycle().value(), charge.nextChargeDate(),
            charge.confidenceScore(), charge.detectionState(), charge.category().value(),
            charge.categorySource(),
            charge.amountChangedAt() == null ? null : charge.amountChangedAt().atOffset(ZoneOffset.UTC),
            charge.variableAmount(), charge.sourceTransactionId(), charge.firstSeenAt(), charge.lastSeenAt(),
            charge.promptVersion(), charge.reasoning());
    }

    public void removeAutomaticExcept(UUID userId, List<String> merchantKeys) {
        if (merchantKeys.isEmpty()) {
            jdbcTemplate.update(
                "DELETE FROM recurring_charges WHERE user_id = ? AND user_override IS NULL", userId);
            return;
        }
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM recurring_charges
                 WHERE user_id = ? AND user_override IS NULL
                   AND NOT (merchant_key = ANY (?))
                """);
            statement.setObject(1, userId);
            Array keys = connection.createArrayOf("text", merchantKeys.toArray(String[]::new));
            statement.setArray(2, keys);
            return statement;
        });
    }

    private static DetectionTransaction mapTransaction(ResultSet resultSet, int rowNumber) throws SQLException {
        return new DetectionTransaction(
            resultSet.getObject("id", UUID.class),
            resultSet.getObject("account_id", UUID.class),
            resultSet.getString("merchant_key"),
            resultSet.getString("merchant_name"),
            resultSet.getString("name"),
            resultSet.getObject("amount", BigDecimal.class),
            resultSet.getString("currency"),
            resultSet.getObject("date", LocalDate.class),
            resultSet.getBoolean("pending"),
            resultSet.getString("pfc_primary"),
            resultSet.getString("pfc_detailed"),
            resultSet.getBoolean("matched_own_transfer"));
    }
}
