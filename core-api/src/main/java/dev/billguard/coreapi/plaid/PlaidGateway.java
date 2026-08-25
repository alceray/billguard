package dev.billguard.coreapi.plaid;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.plaid.client.model.AccountsGetRequest;
import com.plaid.client.model.AccountsGetResponse;
import com.plaid.client.model.CountryCode;
import com.plaid.client.model.ItemPublicTokenExchangeRequest;
import com.plaid.client.model.ItemPublicTokenExchangeResponse;
import com.plaid.client.model.ItemRemoveRequest;
import com.plaid.client.model.JWKPublicKey;
import com.plaid.client.model.LinkTokenCreateRequest;
import com.plaid.client.model.LinkTokenCreateRequestUser;
import com.plaid.client.model.LinkTokenCreateResponse;
import com.plaid.client.model.LinkTokenTransactions;
import com.plaid.client.model.PlaidError;
import com.plaid.client.model.PersonalFinanceCategoryVersion;
import com.plaid.client.model.Products;
import com.plaid.client.model.TransactionsSyncRequest;
import com.plaid.client.model.TransactionsSyncRequestOptions;
import com.plaid.client.model.TransactionsSyncResponse;
import com.plaid.client.model.TransactionsRecurringGetRequest;
import com.plaid.client.model.TransactionsRecurringGetResponse;
import com.plaid.client.model.WebhookVerificationKeyGetRequest;
import com.plaid.client.model.WebhookVerificationKeyGetResponse;
import com.plaid.client.request.PlaidApi;
import dev.billguard.coreapi.config.PlaidProperties;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;

import org.springframework.stereotype.Component;

@Component
public class PlaidGateway {
    private final PlaidApi plaidApi;
    private final PlaidProperties properties;
    private final Gson gson = new Gson();

    public PlaidGateway(PlaidApi plaidApi, PlaidProperties properties) {
        this.plaidApi = plaidApi;
        this.properties = properties;
    }

    public LinkTokenCreateResponse createLinkToken(UUID userId) {
        List<Products> products = properties.products().stream()
            .map(value -> Products.fromValue(value.toLowerCase(Locale.ROOT)))
            .map(value -> requireKnown(value, "Plaid product"))
            .toList();
        List<CountryCode> countryCodes = properties.countryCodes().stream()
            .map(value -> CountryCode.fromValue(value.toUpperCase(Locale.ROOT)))
            .map(value -> requireKnown(value, "Plaid country code"))
            .toList();
        LinkTokenCreateRequest request = new LinkTokenCreateRequest()
            .clientName("BillGuard")
            .language("en")
            .countryCodes(countryCodes)
            .products(products)
            .transactions(new LinkTokenTransactions().daysRequested(730))
            .user(new LinkTokenCreateRequestUser().clientUserId(userId.toString()));
        if (!properties.webhookUrl().isBlank()) {
            request.webhook(properties.webhookUrl());
        }
        return execute(plaidApi.linkTokenCreate(request));
    }

    public ItemPublicTokenExchangeResponse exchangePublicToken(String publicToken) {
        return execute(plaidApi.itemPublicTokenExchange(
            new ItemPublicTokenExchangeRequest().publicToken(publicToken)));
    }

    public AccountsGetResponse getAccounts(String accessToken) {
        return execute(plaidApi.accountsGet(new AccountsGetRequest().accessToken(accessToken)));
    }

    public void removeItem(String accessToken) {
        execute(plaidApi.itemRemove(new ItemRemoveRequest().accessToken(accessToken)));
    }

    public TransactionsSyncResponse syncTransactions(String accessToken, String cursor) {
        TransactionsSyncRequest request = new TransactionsSyncRequest()
            .accessToken(accessToken)
            .count(500)
            .options(new TransactionsSyncRequestOptions()
                .includePersonalFinanceCategory(true)
                .personalFinanceCategoryVersion(PersonalFinanceCategoryVersion.V2)
                .daysRequested(730));
        if (cursor != null && !cursor.isBlank()) {
            request.cursor(cursor);
        }
        return execute(plaidApi.transactionsSync(request));
    }

    public TransactionsRecurringGetResponse recurringTransactions(String accessToken) {
        return execute(plaidApi.transactionsRecurringGet(
            new TransactionsRecurringGetRequest().accessToken(accessToken)));
    }

    public JWKPublicKey getWebhookVerificationKey(String keyId) {
        WebhookVerificationKeyGetResponse response = execute(plaidApi.webhookVerificationKeyGet(
            new WebhookVerificationKeyGetRequest().keyId(keyId)));
        if (response.getKey() == null) {
            throw new PlaidGatewayException("PLAID_INVALID_RESPONSE", "Plaid returned no verification key");
        }
        return response.getKey();
    }

    private <T> T execute(Call<T> call) {
        try {
            Response<T> response = call.execute();
            if (response.isSuccessful() && response.body() != null) {
                return response.body();
            }
            throw mapError(response.errorBody());
        } catch (IOException exception) {
            throw new PlaidGatewayException("PLAID_UNAVAILABLE", "Plaid request failed", exception);
        }
    }

    private PlaidGatewayException mapError(ResponseBody body) {
        if (body == null) {
            return new PlaidGatewayException("PLAID_REQUEST_FAILED", "Plaid request failed");
        }
        try (body) {
            PlaidError error = gson.fromJson(body.string(), PlaidError.class);
            String code = error == null || error.getErrorCode() == null
                ? "PLAID_REQUEST_FAILED"
                : error.getErrorCode();
            return new PlaidGatewayException(code, "Plaid request failed");
        } catch (IOException | JsonSyntaxException exception) {
            return new PlaidGatewayException("PLAID_REQUEST_FAILED", "Plaid request failed", exception);
        }
    }

    private <T extends Enum<T>> T requireKnown(T value, String label) {
        if (value == null || "ENUM_UNKNOWN".equals(value.name())) {
            throw new IllegalStateException(label + " configuration contains an unsupported value");
        }
        return value;
    }
}
