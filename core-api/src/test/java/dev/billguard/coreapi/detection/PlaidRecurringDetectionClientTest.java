package dev.billguard.coreapi.detection;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.plaid.client.model.PersonalFinanceCategory;
import com.plaid.client.model.RecurringTransactionFrequency;
import com.plaid.client.model.TransactionStream;
import com.plaid.client.model.TransactionStreamAmount;
import com.plaid.client.model.TransactionStreamStatus;
import com.plaid.client.model.TransactionsRecurringGetResponse;
import dev.billguard.coreapi.config.TokenCipher;
import dev.billguard.coreapi.plaid.MerchantKeyNormalizer;
import dev.billguard.coreapi.plaid.PlaidGateway;
import dev.billguard.coreapi.plaid.PlaidItem;
import dev.billguard.coreapi.plaid.PlaidItemRepository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlaidRecurringDetectionClientTest {
    private final PlaidItemRepository itemRepository = mock(PlaidItemRepository.class);
    private final PlaidGateway plaidGateway = mock(PlaidGateway.class);
    private final TokenCipher tokenCipher = mock(TokenCipher.class);
    private final PlaidRecurringDetectionClient client = new PlaidRecurringDetectionClient(
        itemRepository, plaidGateway, tokenCipher, new MerchantKeyNormalizer(), new CategoryRules());

    @Test
    void mapsActivePlaidStreamsAndIgnoresTombstones() {
        UUID userId = UUID.randomUUID();
        PlaidItem item = mock(PlaidItem.class);
        when(item.getAccessTokenEnc()).thenReturn("encrypted");
        when(itemRepository.findAllByUserIdOrderByCreatedAtAsc(userId)).thenReturn(List.of(item));
        when(tokenCipher.decrypt("encrypted")).thenReturn("access-token");
        when(plaidGateway.recurringTransactions("access-token")).thenReturn(
            new TransactionsRecurringGetResponse().outflowStreams(List.of(
                stream("NETFLIX.COM", TransactionStreamStatus.MATURE, true),
                stream("OLD SERVICE", TransactionStreamStatus.TOMBSTONED, false))));

        assertThat(client.detect(userId)).singleElement().satisfies(charge -> {
            assertThat(charge.merchantKey()).isEqualTo("netflix");
            assertThat(charge.displayName()).isEqualTo("Netflix.com");
            assertThat(charge.billingCycle()).isEqualTo(BillingCycle.SEMI_MONTHLY);
            assertThat(charge.detectionState()).isEqualTo("confirmed");
            assertThat(charge.category()).isEqualTo(RecurringCategory.STREAMING);
            assertThat(charge.categorySource()).isEqualTo("rule");
            assertThat(charge.reasoning()).isNull();
            assertThat(charge.nextChargeDate()).isEqualTo(LocalDate.of(2026, 9, 15));
        });
    }

    private TransactionStream stream(String merchant, TransactionStreamStatus status, boolean active) {
        return new TransactionStream()
            .merchantName(merchant)
            .description(merchant)
            .frequency(RecurringTransactionFrequency.SEMI_MONTHLY)
            .status(status)
            .isActive(active)
            .firstDate(LocalDate.of(2026, 6, 1))
            .lastDate(LocalDate.of(2026, 9, 1))
            .predictedNextDate(LocalDate.of(2026, 9, 15))
            .lastAmount(new TransactionStreamAmount().amount(15.49).isoCurrencyCode("USD"))
            .averageAmount(new TransactionStreamAmount().amount(15.49).isoCurrencyCode("USD"))
            .personalFinanceCategory(new PersonalFinanceCategory()
                .primary("ENTERTAINMENT")
                .detailed("ENTERTAINMENT_TV_AND_MOVIES"));
    }
}
