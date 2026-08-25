package dev.billguard.coreapi.plaid;

import java.util.UUID;

import dev.billguard.coreapi.recurring.RecurringChargeRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "AUTH0_DOMAIN=test.auth0.com",
    "AUTH0_AUDIENCE=https://api.billguard.test",
    "DATABASE_PASSWORD=test",
    "TOKEN_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class PlaidReadServiceIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlaidReadService readService;

    @Autowired
    private PlaidDataRepository dataRepository;

    @Autowired
    private RecurringChargeRepository recurringChargeRepository;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute(
            "TRUNCATE job_queue, webhook_events, transactions, accounts, plaid_items, users CASCADE");
    }

    @Test
    void transactionFeedCannotExposeAnotherUsersRows() {
        UUID userA = insertUser("auth0|user-a", "a@example.com");
        UUID userB = insertUser("auth0|user-b", "b@example.com");
        UUID accountA = insertAccount(userA, "item-a", "account-a");
        UUID accountB = insertAccount(userB, "item-b", "account-b");
        insertTransaction(accountA, "transaction-a");
        insertTransaction(accountB, "transaction-b");
        jdbcTemplate.update("""
            INSERT INTO recurring_charges (
              user_id, merchant_key, merchant_name, display_name, amount
            ) VALUES (?, 'test merchant', 'Test Merchant', 'Test Merchant', 12.34)
            """, userA);

        var result = readService.transactions("auth0|user-a", null, 50);

        assertThat(result.transactions())
            .extracting(transaction -> transaction.plaidTransactionId())
            .containsExactly("transaction-a");
        assertThat(result.transactions()).singleElement()
            .satisfies(transaction -> assertThat(transaction.recurring()).isTrue());
    }

    @Test
    void deletingAnItemAlsoDeletesItsAccountsAndTransactions() {
        UUID userId = insertUser("auth0|user-a", "a@example.com");
        UUID accountId = insertAccount(userId, "item-a", "account-a");
        insertTransaction(accountId, "transaction-a");
        UUID itemId = jdbcTemplate.queryForObject(
            "SELECT id FROM plaid_items WHERE plaid_item_id = 'item-a'", UUID.class);

        dataRepository.deleteItem(userId, itemId);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM plaid_items WHERE user_id = ?", Integer.class, userId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM accounts WHERE user_id = ?", Integer.class, userId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM transactions", Integer.class)).isZero();
    }

    @Test
    void unmarkingARecurringTransactionUpdatesTheWholeMerchantStream() {
        UUID userId = insertUser("auth0|user-a", "a@example.com");
        UUID accountId = insertAccount(userId, "item-a", "account-a");
        insertTransaction(accountId, "transaction-a");
        insertTransaction(accountId, "transaction-b");
        jdbcTemplate.update("""
            INSERT INTO recurring_charges (
              user_id, merchant_key, merchant_name, display_name, amount
            ) VALUES (?, 'test merchant', 'Test Merchant', 'Test Merchant', 12.34)
            """, userId);
        UUID transactionId = jdbcTemplate.queryForObject(
            "SELECT id FROM transactions WHERE plaid_transaction_id = 'transaction-a'", UUID.class);

        boolean updated = recurringChargeRepository.unmarkTransaction(userId, transactionId);

        assertThat(updated).isTrue();
        assertThat(readService.transactions("auth0|user-a", null, 50).transactions())
            .allSatisfy(transaction -> assertThat(transaction.recurring()).isFalse());
        assertThat(jdbcTemplate.queryForObject("""
            SELECT user_override FROM recurring_charges
             WHERE user_id = ? AND merchant_key = 'test merchant'
            """, Boolean.class, userId)).isFalse();
    }

    private UUID insertUser(String subject, String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, auth0_sub, email) VALUES (?, ?, ?)", id, subject, email);
        return id;
    }

    private UUID insertAccount(UUID userId, String itemId, String accountId) {
        jdbcTemplate.update("""
            INSERT INTO plaid_items (user_id, plaid_item_id, access_token_enc)
            VALUES (?, ?, 'v1:test:test')
            """, userId, itemId);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO accounts (id, user_id, plaid_account_id, plaid_item_id, name, type)
            VALUES (?, ?, ?, ?, 'Checking', 'depository')
            """, id, userId, accountId, itemId);
        return id;
    }

    private void insertTransaction(UUID accountId, String transactionId) {
        jdbcTemplate.update("""
            INSERT INTO transactions (
              account_id, plaid_transaction_id, amount, currency, name, date, merchant_key
            ) VALUES (?, ?, 12.34, 'USD', 'Test Merchant', DATE '2026-08-08', 'test merchant')
            """, accountId, transactionId);
    }
}
