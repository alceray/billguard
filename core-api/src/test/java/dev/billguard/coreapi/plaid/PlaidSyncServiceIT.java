package dev.billguard.coreapi.plaid;

import java.util.UUID;
import java.time.OffsetDateTime;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.billguard.coreapi.config.TokenCipher;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class PlaidSyncServiceIT {
    private static final String ITEM_ID = "item-test";
    private static final String ACCOUNT_ID = "account-test";
    private static final WireMockServer PLAID = new WireMockServer(options().dynamicPort());

    static {
        PLAID.start();
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("AUTH0_DOMAIN", () -> "test.auth0.com");
        registry.add("AUTH0_AUDIENCE", () -> "https://api.billguard.test");
        registry.add("DATABASE_PASSWORD", () -> "test");
        registry.add("TOKEN_ENCRYPTION_KEY", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("billguard.plaid.base-url", PLAID::baseUrl);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TokenCipher tokenCipher;

    @Autowired
    private PlaidSyncService syncService;

    @BeforeEach
    void setUp() {
        PLAID.resetAll();
        jdbcTemplate.execute("TRUNCATE job_queue, webhook_events, transactions, accounts, plaid_items, users CASCADE");
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO users (id, auth0_sub, email) VALUES (?, 'auth0|sync', 'sync@example.com')", userId);
        jdbcTemplate.update("""
            INSERT INTO plaid_items (user_id, plaid_item_id, access_token_enc)
            VALUES (?, ?, ?)
            """, userId, ITEM_ID, tokenCipher.encrypt("access-sandbox-test"));
        jdbcTemplate.update("""
            INSERT INTO accounts (user_id, plaid_account_id, plaid_item_id, name, type)
            VALUES (?, ?, ?, 'Checking', 'depository')
            """, userId, ACCOUNT_ID, ITEM_ID);
    }

    @AfterAll
    static void stopWireMock() {
        PLAID.stop();
    }

    @Test
    void twoPagesAreIdempotentAndRemovedRowsAreDeleted() {
        stubTwoPages();

        syncService.syncItem(ITEM_ID);

        assertThat(countTransactions()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM job_queue WHERE type = 'detect_streams'", Integer.class)).isEqualTo(1);
        assertThat(cursor()).isEqualTo("cursor-2");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT merchant_key FROM transactions WHERE plaid_transaction_id = 'tx-blue-bottle'",
            String.class)).isEqualTo("blue bottle");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT pfc_detailed FROM transactions WHERE plaid_transaction_id = 'tx-blue-bottle'",
            String.class)).isEqualTo("FOOD_AND_DRINK_COFFEE");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT transaction_datetime FROM transactions WHERE plaid_transaction_id = 'tx-blue-bottle'",
            OffsetDateTime.class)).isEqualTo(OffsetDateTime.parse("2026-08-01T14:45:00Z"));
        assertThat(jdbcTemplate.queryForObject("""
            SELECT raw_payload -> 'personal_finance_category' ->> 'primary'
              FROM transactions WHERE plaid_transaction_id = 'tx-blue-bottle'
            """, String.class)).isEqualTo("FOOD_AND_DRINK");

        jdbcTemplate.update("UPDATE plaid_items SET cursor = NULL WHERE plaid_item_id = ?", ITEM_ID);
        syncService.syncItem(ITEM_ID);
        assertThat(countTransactions()).isEqualTo(2);

        PLAID.resetAll();
        PLAID.stubFor(post(urlEqualTo("/transactions/sync"))
            .willReturn(json(page("cursor-3", false, "", "", "tx-blue-bottle"))));
        syncService.syncItem(ITEM_ID);

        assertThat(countTransactions()).isEqualTo(1);
        assertThat(cursor()).isEqualTo("cursor-3");
    }

    @Test
    void failureAfterFirstPageResumesFromCommittedCursor() {
        PLAID.stubFor(post(urlEqualTo("/transactions/sync")).atPriority(10)
            .willReturn(json(page("resume-cursor", true, "tx-first", "FIRST MERCHANT", ""))));
        PLAID.stubFor(post(urlEqualTo("/transactions/sync")).atPriority(1)
            .withRequestBody(matchingJsonPath("$.cursor", equalTo("resume-cursor")))
            .willReturn(aResponse().withStatus(500).withHeader("Content-Type", "application/json")
                .withBody("{\"error_code\":\"INTERNAL_SERVER_ERROR\",\"error_type\":\"API_ERROR\"}")));

        assertThatThrownBy(() -> syncService.syncItem(ITEM_ID))
            .isInstanceOf(PlaidGatewayException.class);
        assertThat(countTransactions()).isEqualTo(1);
        assertThat(cursor()).isEqualTo("resume-cursor");

        PLAID.resetAll();
        PLAID.stubFor(post(urlEqualTo("/transactions/sync"))
            .withRequestBody(matchingJsonPath("$.cursor", equalTo("resume-cursor")))
            .willReturn(json(page("done-cursor", false, "tx-second", "SECOND MERCHANT", ""))));

        syncService.syncItem(ITEM_ID);
        assertThat(countTransactions()).isEqualTo(2);
        assertThat(cursor()).isEqualTo("done-cursor");
    }

    private void stubTwoPages() {
        PLAID.stubFor(post(urlEqualTo("/transactions/sync")).atPriority(10)
            .willReturn(json(page("cursor-1", true, "tx-blue-bottle", "SQ *BLUE BOTTLE #47", ""))));
        PLAID.stubFor(post(urlEqualTo("/transactions/sync")).atPriority(1)
            .withRequestBody(matchingJsonPath("$.cursor", equalTo("cursor-1")))
            .willReturn(json(page("cursor-2", false, "tx-netflix", "NETFLIX.COM 866-579-7172", ""))));
    }

    private com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    private String page(
        String nextCursor,
        boolean hasMore,
        String transactionId,
        String merchant,
        String removedId
    ) {
        String added = transactionId.isBlank() ? "" : """
            {
              "account_id":"%s",
              "amount":15.49,
              "iso_currency_code":"USD",
              "category":["Shops"],
              "personal_finance_category":{
                "primary":"FOOD_AND_DRINK",
                "detailed":"FOOD_AND_DRINK_COFFEE",
                "confidence_level":"VERY_HIGH"
              },
              "date":"2026-08-01",
              "authorized_datetime":"2026-08-01T14:45:00Z",
              "datetime":"2026-08-01T15:30:00Z",
              "name":"%s",
              "merchant_name":"%s",
              "pending":false,
              "transaction_id":"%s",
              "payment_channel":"online"
            }
            """.formatted(ACCOUNT_ID, merchant, merchant, transactionId);
        String removed = removedId.isBlank() ? "" : "{\"transaction_id\":\"" + removedId + "\"}";
        return """
            {
              "accounts":[],
              "added":[%s],
              "modified":[],
              "removed":[%s],
              "next_cursor":"%s",
              "has_more":%s,
              "request_id":"wiremock"
            }
            """.formatted(added, removed, nextCursor, hasMore);
    }

    private int countTransactions() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class);
    }

    private String cursor() {
        return jdbcTemplate.queryForObject(
            "SELECT cursor FROM plaid_items WHERE plaid_item_id = ?", String.class, ITEM_ID);
    }
}
