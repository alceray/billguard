package dev.billguard.coreapi.detection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.billguard.coreapi.recurring.PromoteTransactionRequest;
import dev.billguard.coreapi.recurring.RecurringChargePatchRequest;
import dev.billguard.coreapi.recurring.RecurringChargeService;
import dev.billguard.coreapi.common.HttpException;
import dev.billguard.coreapi.plaid.PlaidGatewayException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "AUTH0_DOMAIN=test.auth0.com",
    "AUTH0_AUDIENCE=https://api.billguard.test",
    "DATABASE_PASSWORD=test",
    "TOKEN_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class RecurringChargeDetectorIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecurringChargeDetectionService detectionService;

    @Autowired
    private RecurringChargeService recurringChargeService;

    @MockitoBean
    private AiClassificationClient aiClient;

    @MockitoBean
    private PlaidRecurringDetectionClient plaidClient;

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        when(aiClient.classify(anyList())).thenReturn(Map.of());
        when(plaidClient.detect(any(UUID.class))).thenReturn(List.of());
        jdbcTemplate.execute(
            "TRUNCATE job_queue, recurring_charges, transactions, accounts, plaid_items, users CASCADE");
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, auth0_sub, email) VALUES (?, 'auth0|detect', 'detect@example.com')",
            userId);
        jdbcTemplate.update("""
            INSERT INTO plaid_items (user_id, plaid_item_id, access_token_enc)
            VALUES (?, 'item-detect', 'encrypted')
            """, userId);
        jdbcTemplate.update("""
            INSERT INTO accounts (id, user_id, plaid_account_id, plaid_item_id, name, type)
            VALUES (?, ?, 'account-detect', 'item-detect', 'Checking', 'depository')
            """, accountId, userId);
    }

    @Test
    void detectionIsIdempotentAndRulesOnlyWhenAiHasNoResults() {
        insertMonthly("netflix", "NETFLIX.COM", "15.49");

        assertThat(detectionService.detectUser(userId)).isEqualTo(1);
        assertThat(detectionService.detectUser(userId)).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM recurring_charges WHERE user_id = ?", Integer.class, userId))
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT detection_state FROM recurring_charges WHERE user_id = ?", String.class, userId))
            .isEqualTo("confirmed");
    }

    @Test
    void amountChangeTimestampIsPersisted() {
        insertTransaction("adobe", "ADOBE", "10.00", LocalDate.of(2026, 6, 1));
        insertTransaction("adobe", "ADOBE", "10.00", LocalDate.of(2026, 7, 1));
        insertTransaction("adobe", "ADOBE", "10.50", LocalDate.of(2026, 8, 1));

        assertThat(detectionService.detectUser(userId)).isEqualTo(1);

        OffsetDateTime changedAt = jdbcTemplate.queryForObject(
            "SELECT amount_changed_at FROM recurring_charges WHERE user_id = ?",
            OffsetDateTime.class, userId);
        assertThat(changedAt.toInstant()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
    }

    @Test
    void explicitRejectionIsNotOverwrittenByLaterDetection() {
        insertMonthly("netflix", "NETFLIX.COM", "15.49");
        detectionService.detectUser(userId);
        UUID chargeId = jdbcTemplate.queryForObject(
            "SELECT id FROM recurring_charges WHERE user_id = ?", UUID.class, userId);
        jdbcTemplate.update(
            "UPDATE recurring_charges SET user_override = FALSE, amount = 1.23 WHERE id = ?", chargeId);

        detectionService.detectUser(userId);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT amount FROM recurring_charges WHERE id = ?", BigDecimal.class, chargeId))
            .isEqualByComparingTo("1.23");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT user_override FROM recurring_charges WHERE id = ?", Boolean.class, chargeId)).isFalse();
    }

    @Test
    void manuallyPromotedTransactionSurvivesRulesOnlyRedetection() {
        UUID transactionId = insertTransaction(
            "coffee", "CORNER COFFEE", "5.75", LocalDate.of(2026, 8, 4));

        var promoted = recurringChargeService.promote(
            "auth0|detect", new PromoteTransactionRequest(
                transactionId, RecurringCategory.SHOPPING_MEMBERSHIP));
        detectionService.detectUser(userId);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT detection_state FROM recurring_charges WHERE id = ?", String.class, promoted.id()))
            .isEqualTo("user_confirmed");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT category FROM recurring_charges WHERE id = ?", String.class, promoted.id()))
            .isEqualTo("shopping_membership");
    }

    @Test
    void provisionalPromotesInPlaceAndUserCategorySurvives() {
        insertTransaction("adobe", "ADOBE", "10.00", LocalDate.of(2026, 6, 1));
        insertTransaction("adobe", "ADOBE", "10.00", LocalDate.of(2026, 7, 1));
        detectionService.detectUser(userId);
        UUID chargeId = jdbcTemplate.queryForObject(
            "SELECT id FROM recurring_charges WHERE user_id = ?", UUID.class, userId);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT detection_state FROM recurring_charges WHERE id = ?", String.class, chargeId))
            .isEqualTo("provisional");

        recurringChargeService.update(
            "auth0|detect", chargeId, new RecurringChargePatchRequest(null, RecurringCategory.FINANCE));
        insertTransaction("adobe", "ADOBE", "10.00", LocalDate.of(2026, 8, 1));
        detectionService.detectUser(userId);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT id FROM recurring_charges WHERE user_id = ?", UUID.class, userId)).isEqualTo(chargeId);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT detection_state FROM recurring_charges WHERE id = ?", String.class, chargeId))
            .isEqualTo("confirmed");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT category FROM recurring_charges WHERE id = ?", String.class, chargeId))
            .isEqualTo("finance");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT category_source FROM recurring_charges WHERE id = ?", String.class, chargeId))
            .isEqualTo("user");
    }

    @Test
    void usesPlaidAsBaselineAndAddsLocalDetections() {
        insertMonthly("netflix", "NETFLIX.COM", "15.49");
        when(plaidClient.detect(userId)).thenReturn(List.of(
            plaidCharge("netflix", "Netflix"),
            plaidCharge("adobe", "Adobe")));

        assertThat(detectionService.detectUser(userId)).isEqualTo(2);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM recurring_charges WHERE user_id = ?", Integer.class, userId))
            .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT amount FROM recurring_charges
             WHERE user_id = ? AND merchant_key = 'netflix'
            """, BigDecimal.class, userId)).isEqualByComparingTo("12.00");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT display_name FROM recurring_charges
             WHERE user_id = ? AND merchant_key = 'adobe'
            """, String.class, userId)).isEqualTo("Adobe");
        verify(aiClient).classify(argThat(List::isEmpty));

        when(plaidClient.detect(userId)).thenReturn(List.of());
        assertThat(detectionService.detectUser(userId)).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM recurring_charges WHERE user_id = ?", Integer.class, userId))
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT amount FROM recurring_charges
             WHERE user_id = ? AND merchant_key = 'netflix'
            """, BigDecimal.class, userId)).isEqualByComparingTo("15.49");
    }

    @Test
    void plaidFailurePreservesLastSuccessfulBaseline() {
        when(plaidClient.detect(userId)).thenReturn(List.of(plaidCharge("adobe", "Adobe")));
        detectionService.detectUser(userId);
        when(plaidClient.detect(userId)).thenThrow(
            new PlaidGatewayException("PLAID_UNAVAILABLE", "Plaid request failed"));

        detectionService.detectUser(userId);

        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM recurring_charges
             WHERE user_id = ? AND merchant_key = 'adobe'
            """, Integer.class, userId)).isEqualTo(1);
    }

    @Test
    void overridesAndPromotionAreScopedToAuthenticatedUser() {
        insertMonthly("netflix", "NETFLIX.COM", "15.49");
        detectionService.detectUser(userId);
        UUID chargeId = jdbcTemplate.queryForObject(
            "SELECT id FROM recurring_charges WHERE user_id = ?", UUID.class, userId);
        UUID transactionId = jdbcTemplate.queryForObject(
            "SELECT id FROM transactions WHERE account_id = ? LIMIT 1", UUID.class, accountId);
        jdbcTemplate.update(
            "INSERT INTO users (id, auth0_sub, email) VALUES (?, 'auth0|other', 'other@example.com')",
            UUID.randomUUID());

        assertThatThrownBy(() -> recurringChargeService.update(
            "auth0|other", chargeId, new RecurringChargePatchRequest(false, null)))
            .isInstanceOf(HttpException.class);
        assertThatThrownBy(() -> recurringChargeService.promote(
            "auth0|other", new PromoteTransactionRequest(transactionId, RecurringCategory.STREAMING)))
            .isInstanceOf(HttpException.class);
    }

    private void insertMonthly(String merchantKey, String merchant, String amount) {
        insertTransaction(merchantKey, merchant, amount, LocalDate.of(2026, 6, 1));
        insertTransaction(merchantKey, merchant, amount, LocalDate.of(2026, 7, 1));
        insertTransaction(merchantKey, merchant, amount, LocalDate.of(2026, 8, 1));
    }

    private UUID insertTransaction(String merchantKey, String merchant, String amount, LocalDate date) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO transactions (
              id, account_id, plaid_transaction_id, amount, currency, merchant_name,
              name, date, pending, merchant_key
            ) VALUES (?, ?, ?, ?, 'USD', ?, ?, ?, FALSE, ?)
            """, id, accountId, "plaid-" + id, new BigDecimal(amount), merchant, merchant, date, merchantKey);
        return id;
    }

    private DetectedCharge plaidCharge(String merchantKey, String displayName) {
        return new DetectedCharge(
            merchantKey, displayName, displayName, new BigDecimal("12.00"), null, "USD",
            BillingCycle.MONTHLY, LocalDate.of(2026, 9, 1), new BigDecimal("0.90"), "confirmed",
            RecurringCategory.SOFTWARE, "rule", null, false, null,
            LocalDate.of(2026, 6, 1), LocalDate.of(2026, 8, 1), null, null);
    }
}
