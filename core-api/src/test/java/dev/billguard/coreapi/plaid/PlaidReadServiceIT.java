package dev.billguard.coreapi.plaid;

import java.util.UUID;

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

        var result = readService.transactions("auth0|user-a", null, 50);

        assertThat(result.transactions())
            .extracting(transaction -> transaction.plaidTransactionId())
            .containsExactly("transaction-a");
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
