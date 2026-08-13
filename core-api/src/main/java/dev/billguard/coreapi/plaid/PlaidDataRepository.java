package dev.billguard.coreapi.plaid;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.plaid.client.model.AccountBase;
import com.plaid.client.model.RemovedTransaction;
import com.plaid.client.model.Transaction;
import dev.billguard.coreapi.common.HttpException;
import dev.billguard.coreapi.plaid.dto.TransactionResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PlaidDataRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MerchantKeyNormalizer merchantKeyNormalizer;

    public PlaidDataRepository(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        MerchantKeyNormalizer merchantKeyNormalizer
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.merchantKeyNormalizer = merchantKeyNormalizer;
    }

    public void upsertItem(
        UUID userId,
        String plaidItemId,
        String encryptedAccessToken,
        String institutionId,
        String institutionName
    ) {
        int changed = jdbcTemplate.update("""
            INSERT INTO plaid_items (
              user_id, plaid_item_id, access_token_enc, institution_id, institution_name
            ) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (plaid_item_id) DO UPDATE
               SET access_token_enc = EXCLUDED.access_token_enc,
                   institution_id = EXCLUDED.institution_id,
                   institution_name = EXCLUDED.institution_name,
                   status = 'healthy', last_error = NULL, updated_at = NOW()
             WHERE plaid_items.user_id = EXCLUDED.user_id
            """, userId, plaidItemId, encryptedAccessToken, blankToNull(institutionId),
            blankToNull(institutionName));
        if (changed != 1) {
            throw new HttpException(HttpStatus.CONFLICT, "Plaid item is already linked to another user");
        }
    }

    public void upsertAccount(UUID userId, String plaidItemId, AccountBase account) {
        jdbcTemplate.update("""
            INSERT INTO accounts (
              user_id, plaid_account_id, plaid_item_id, name, official_name, type, subtype, mask
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id, plaid_account_id) DO UPDATE
               SET plaid_item_id = EXCLUDED.plaid_item_id,
                   name = EXCLUDED.name,
                   official_name = EXCLUDED.official_name,
                   type = EXCLUDED.type,
                   subtype = EXCLUDED.subtype,
                   mask = EXCLUDED.mask
            """, userId, account.getAccountId(), plaidItemId, account.getName(), account.getOfficialName(),
            account.getType() == null ? "other" : account.getType().toString(),
            account.getSubtype() == null ? null : account.getSubtype().toString(), account.getMask());
    }

    public void applyTransaction(String plaidItemId, Transaction transaction) {
        String merchantKey = merchantKeyNormalizer.normalize(transaction.getMerchantName(), transaction.getName());
        List<String> category = transaction.getCategory();
        String rawPayload = rawPayload(transaction, merchantKey);
        int changed = jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO transactions (
                  account_id, plaid_transaction_id, amount, currency, merchant_name, name, date,
                  pending, category, raw_payload, pending_transaction_id, merchant_key
                )
                SELECT account.id, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?
                  FROM accounts account
                 WHERE account.plaid_account_id = ? AND account.plaid_item_id = ?
                ON CONFLICT (plaid_transaction_id) DO UPDATE
                   SET account_id = EXCLUDED.account_id,
                       amount = EXCLUDED.amount,
                       currency = EXCLUDED.currency,
                       merchant_name = EXCLUDED.merchant_name,
                       name = EXCLUDED.name,
                       date = EXCLUDED.date,
                       pending = EXCLUDED.pending,
                       category = EXCLUDED.category,
                       raw_payload = EXCLUDED.raw_payload,
                       pending_transaction_id = EXCLUDED.pending_transaction_id,
                       merchant_key = EXCLUDED.merchant_key,
                       updated_at = NOW()
                """);
            statement.setString(1, transaction.getTransactionId());
            statement.setBigDecimal(2, BigDecimal.valueOf(transaction.getAmount()));
            statement.setString(3, currency(transaction));
            statement.setString(4, transaction.getMerchantName());
            statement.setString(5, transaction.getName());
            statement.setObject(6, transaction.getDate());
            statement.setBoolean(7, Boolean.TRUE.equals(transaction.getPending()));
            if (category == null) {
                statement.setNull(8, Types.ARRAY);
            } else {
                Array sqlArray = connection.createArrayOf("text", category.toArray(String[]::new));
                statement.setArray(8, sqlArray);
            }
            statement.setString(9, rawPayload);
            statement.setString(10, transaction.getPendingTransactionId());
            statement.setString(11, merchantKey);
            statement.setString(12, transaction.getAccountId());
            statement.setString(13, plaidItemId);
            return statement;
        });
        if (changed != 1) {
            throw new IllegalStateException("Plaid transaction referenced an unknown account");
        }
    }

    public void removeTransaction(String plaidItemId, RemovedTransaction removed) {
        jdbcTemplate.update("""
            DELETE FROM transactions transaction
             USING accounts account
             WHERE transaction.account_id = account.id
               AND account.plaid_item_id = ?
               AND transaction.plaid_transaction_id = ?
            """, plaidItemId, removed.getTransactionId());
    }

    public void advanceCursor(String plaidItemId, String nextCursor) {
        jdbcTemplate.update("""
            UPDATE plaid_items
               SET cursor = ?, last_synced_at = NOW(), status = 'healthy', last_error = NULL, updated_at = NOW()
             WHERE plaid_item_id = ?
            """, nextCursor, plaidItemId);
    }

    public void resetCursor(String plaidItemId, String cursor) {
        jdbcTemplate.update("UPDATE plaid_items SET cursor = ?, updated_at = NOW() WHERE plaid_item_id = ?",
            cursor, plaidItemId);
    }

    public void markItemError(String plaidItemId, String status, String error) {
        jdbcTemplate.update("""
            UPDATE plaid_items
               SET status = ?, last_error = ?, updated_at = NOW()
             WHERE plaid_item_id = ?
            """, status, truncate(error), plaidItemId);
    }

    public boolean recordWebhook(
        String eventId,
        String webhookType,
        String webhookCode,
        String itemId,
        String rawBody
    ) {
        return jdbcTemplate.update("""
            INSERT INTO webhook_events (
              event_id, webhook_type, webhook_code, item_id, payload, processed, processed_at
            ) VALUES (?, ?, ?, ?, CAST(? AS jsonb), TRUE, NOW())
            ON CONFLICT (event_id) DO NOTHING
            """, eventId, webhookType, webhookCode, itemId, rawBody) == 1;
    }

    public List<TransactionResponse> findTransactions(
        UUID userId,
        LocalDate beforeDate,
        UUID beforeId,
        int limit
    ) {
        String cursorPredicate = beforeDate == null
            ? ""
            : " AND (transaction.date, transaction.id) < (?, ?)";
        List<Object> parameters = new ArrayList<>();
        parameters.add(userId);
        if (beforeDate != null) {
            parameters.add(beforeDate);
            parameters.add(beforeId);
        }
        parameters.add(limit);
        return jdbcTemplate.query("""
            SELECT transaction.id, transaction.plaid_transaction_id, transaction.amount,
                   transaction.currency, transaction.merchant_name, transaction.merchant_key,
                   transaction.name, transaction.date, transaction.pending,
                   account.name AS account_name, item.institution_name
              FROM transactions transaction
              JOIN accounts account ON account.id = transaction.account_id
              JOIN plaid_items item ON item.plaid_item_id = account.plaid_item_id
             WHERE account.user_id = ?
            """ + cursorPredicate + """
             ORDER BY transaction.date DESC, transaction.id DESC
             LIMIT ?
            """, (resultSet, rowNumber) -> new TransactionResponse(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("plaid_transaction_id"),
                resultSet.getBigDecimal("amount"),
                resultSet.getString("currency"),
                resultSet.getString("merchant_name"),
                resultSet.getString("merchant_key"),
                resultSet.getString("name"),
                resultSet.getObject("date", LocalDate.class),
                resultSet.getBoolean("pending"),
                resultSet.getString("account_name"),
                resultSet.getString("institution_name")
            ), parameters.toArray());
    }

    private String rawPayload(Transaction transaction, String merchantKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transaction_id", transaction.getTransactionId());
        payload.put("account_id", transaction.getAccountId());
        payload.put("amount", transaction.getAmount());
        payload.put("iso_currency_code", transaction.getIsoCurrencyCode());
        payload.put("unofficial_currency_code", transaction.getUnofficialCurrencyCode());
        payload.put("merchant_name", transaction.getMerchantName());
        payload.put("name", transaction.getName());
        payload.put("date", transaction.getDate());
        payload.put("pending", transaction.getPending());
        payload.put("pending_transaction_id", transaction.getPendingTransactionId());
        payload.put("category", transaction.getCategory());
        payload.put("merchant_key", merchantKey);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize Plaid transaction", exception);
        }
    }

    private String currency(Transaction transaction) {
        if (transaction.getIsoCurrencyCode() != null) {
            return transaction.getIsoCurrencyCode();
        }
        if (transaction.getUnofficialCurrencyCode() != null) {
            return transaction.getUnofficialCurrencyCode();
        }
        return "USD";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1_000 ? value : value.substring(0, 1_000);
    }
}
