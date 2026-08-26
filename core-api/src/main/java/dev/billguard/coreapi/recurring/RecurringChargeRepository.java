package dev.billguard.coreapi.recurring;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RecurringChargeRepository {
    private final JdbcTemplate jdbcTemplate;

    public RecurringChargeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<RecurringChargeResponse> findAll(UUID userId, String category) {
        String filter = category == null ? "" : " AND category = ?";
        Object[] parameters = category == null ? new Object[] {userId} : new Object[] {userId, category};
        return jdbcTemplate.query("""
            SELECT id, merchant_key, merchant_name, display_name, amount, previous_amount,
                   currency, billing_cycle, next_charge_date, confidence_score, model_confidence,
                   detection_state, category, category_source, amount_changed_at, is_variable_amount,
                   first_seen_at, last_seen_at, user_override
              FROM recurring_charges
             WHERE user_id = ? AND user_override IS DISTINCT FROM FALSE
            """ + filter + " ORDER BY detection_state, next_charge_date NULLS LAST, display_name",
            RecurringChargeRepository::mapCharge, parameters);
    }

    public boolean update(UUID userId, UUID id, Boolean recurring, String category) {
        return jdbcTemplate.update("""
            UPDATE recurring_charges
               SET user_override = CASE WHEN CAST(? AS BOOLEAN) IS NULL THEN user_override ELSE ? END,
                   detection_state = CASE WHEN CAST(? AS BOOLEAN) = TRUE THEN 'user_confirmed' ELSE detection_state END,
                   category = COALESCE(CAST(? AS TEXT), category),
                   category_source = CASE WHEN CAST(? AS TEXT) IS NULL THEN category_source ELSE 'user' END,
                   feedback_type = CASE
                     WHEN CAST(? AS BOOLEAN) = TRUE THEN 'confirmation'
                     WHEN CAST(? AS BOOLEAN) = FALSE THEN 'false_positive'
                     ELSE feedback_type
                   END,
                   updated_at = NOW()
             WHERE id = ? AND user_id = ?
            """, recurring, recurring, recurring, category, category,
            recurring, recurring, id, userId) == 1;
    }

    public RecurringChargeResponse findById(UUID userId, UUID id) {
        return jdbcTemplate.query("""
            SELECT id, merchant_key, merchant_name, display_name, amount, previous_amount,
                   currency, billing_cycle, next_charge_date, confidence_score, model_confidence,
                   detection_state, category, category_source, amount_changed_at, is_variable_amount,
                   first_seen_at, last_seen_at, user_override
              FROM recurring_charges WHERE id = ? AND user_id = ?
            """, RecurringChargeRepository::mapCharge, id, userId).stream().findFirst().orElse(null);
    }

    public UUID promoteTransaction(UUID userId, UUID transactionId, String category) {
        return jdbcTemplate.query("""
            INSERT INTO recurring_charges (
              user_id, merchant_key, merchant_name, display_name, amount, currency,
              confidence_score, status, user_override, detection_state, category,
              category_source, source_transaction_id, first_seen_at, last_seen_at, feedback_type
            )
            SELECT account.user_id, transaction.merchant_key,
                   COALESCE(transaction.merchant_name, transaction.name),
                   COALESCE(transaction.merchant_name, transaction.name), transaction.amount,
                   transaction.currency, 1.00, 'active', TRUE, 'user_confirmed', ?, 'user',
                   transaction.id, transaction.date, transaction.date, 'false_negative'
              FROM transactions transaction
              JOIN accounts account ON account.id = transaction.account_id
             WHERE transaction.id = ? AND account.user_id = ? AND transaction.merchant_key IS NOT NULL
            ON CONFLICT (user_id, merchant_key) DO UPDATE
               SET user_override = TRUE,
                   detection_state = 'user_confirmed',
                   category = EXCLUDED.category,
                   category_source = 'user',
                   feedback_type = 'false_negative',
                   source_transaction_id = EXCLUDED.source_transaction_id,
                   updated_at = NOW()
            RETURNING id
            """, (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
            category, transactionId, userId).stream().findFirst().orElse(null);
    }

    public boolean unmarkTransaction(UUID userId, UUID transactionId) {
        return jdbcTemplate.update("""
            UPDATE recurring_charges recurring
               SET user_override = FALSE,
                   feedback_type = 'false_positive',
                   updated_at = NOW()
              FROM transactions transaction
              JOIN accounts account ON account.id = transaction.account_id
             WHERE transaction.id = ?
               AND account.user_id = ?
               AND recurring.user_id = account.user_id
               AND recurring.merchant_key = transaction.merchant_key
            """, transactionId, userId) == 1;
    }

    public AccuracyResponse accuracy(UUID userId) {
        return jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FILTER (WHERE user_override IS NULL) AS system_detected,
                   COUNT(*) FILTER (WHERE feedback_type = 'confirmation') AS confirmed_feedback,
                   COUNT(*) FILTER (WHERE feedback_type = 'false_positive') AS false_positives,
                   COUNT(*) FILTER (WHERE feedback_type = 'false_negative') AS false_negatives
              FROM recurring_charges WHERE user_id = ?
            """, (resultSet, rowNumber) -> {
                int system = resultSet.getInt("system_detected");
                int confirmed = resultSet.getInt("confirmed_feedback");
                int rejected = resultSet.getInt("false_positives");
                int missed = resultSet.getInt("false_negatives");
                int feedback = confirmed + rejected;
                BigDecimal precision = feedback == 0
                    ? BigDecimal.ZERO
                    : BigDecimal.valueOf(confirmed).divide(BigDecimal.valueOf(feedback), 2, RoundingMode.HALF_UP);
                return new AccuracyResponse(system, confirmed, rejected, missed, precision);
            }, userId);
    }

    private static RecurringChargeResponse mapCharge(ResultSet resultSet, int rowNumber) throws SQLException {
        String category = resultSet.getString("category");
        String cycle = resultSet.getString("billing_cycle");
        BigDecimal amount = resultSet.getBigDecimal("amount");
        return new RecurringChargeResponse(
            resultSet.getObject("id", UUID.class), resultSet.getString("merchant_key"),
            resultSet.getString("merchant_name"), resultSet.getString("display_name"), amount,
            resultSet.getBigDecimal("previous_amount"), resultSet.getString("currency"), cycle,
            resultSet.getObject("next_charge_date", LocalDate.class), resultSet.getBigDecimal("confidence_score"),
            resultSet.getBigDecimal("model_confidence"),
            resultSet.getString("detection_state"), category, resultSet.getString("category_source"),
            resultSet.getTimestamp("amount_changed_at") == null
                ? null : resultSet.getTimestamp("amount_changed_at").toInstant(),
            resultSet.getBoolean("is_variable_amount"),
            resultSet.getObject("first_seen_at", LocalDate.class), resultSet.getObject("last_seen_at", LocalDate.class),
            resultSet.getObject("user_override", Boolean.class), group(category), monthly(amount, cycle));
    }

    private static String group(String category) {
        return switch (category == null ? "other" : category.toLowerCase(Locale.ROOT)) {
            case "insurance", "telecom", "utilities" -> "bills";
            case "housing", "loan" -> "fixed_expenses";
            default -> "subscriptions";
        };
    }

    private static BigDecimal monthly(BigDecimal amount, String cycle) {
        if (amount == null || cycle == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal factor = switch (cycle) {
            case "weekly" -> new BigDecimal("4.333333");
            case "biweekly" -> new BigDecimal("2.166667");
            case "semi_monthly" -> new BigDecimal("2.000000");
            case "monthly" -> BigDecimal.ONE;
            case "quarterly" -> new BigDecimal("0.333333");
            case "annual" -> new BigDecimal("0.083333");
            default -> BigDecimal.ZERO;
        };
        return amount.multiply(factor).setScale(2, RoundingMode.HALF_UP);
    }
}
