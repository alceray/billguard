package dev.billguard.coreapi.detection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.plaid.client.model.PersonalFinanceCategory;
import com.plaid.client.model.RecurringTransactionFrequency;
import com.plaid.client.model.TransactionStream;
import com.plaid.client.model.TransactionStreamAmount;
import com.plaid.client.model.TransactionStreamStatus;
import dev.billguard.coreapi.config.TokenCipher;
import dev.billguard.coreapi.plaid.MerchantKeyNormalizer;
import dev.billguard.coreapi.plaid.PlaidGateway;
import dev.billguard.coreapi.plaid.PlaidItem;
import dev.billguard.coreapi.plaid.PlaidItemRepository;

import org.springframework.stereotype.Component;

@Component
public class PlaidRecurringDetectionClient {
    private static final BigDecimal MATURE_CONFIDENCE = new BigDecimal("0.90");
    private static final BigDecimal EARLY_CONFIDENCE = new BigDecimal("0.65");
    private static final BigDecimal UNKNOWN_CONFIDENCE = new BigDecimal("0.50");

    private final PlaidItemRepository itemRepository;
    private final PlaidGateway plaidGateway;
    private final TokenCipher tokenCipher;
    private final MerchantKeyNormalizer normalizer;
    private final CategoryRules categoryRules;

    public PlaidRecurringDetectionClient(
        PlaidItemRepository itemRepository,
        PlaidGateway plaidGateway,
        TokenCipher tokenCipher,
        MerchantKeyNormalizer normalizer,
        CategoryRules categoryRules
    ) {
        this.itemRepository = itemRepository;
        this.plaidGateway = plaidGateway;
        this.tokenCipher = tokenCipher;
        this.normalizer = normalizer;
        this.categoryRules = categoryRules;
    }

    public List<DetectedCharge> detect(UUID userId) {
        Map<String, DetectedCharge> detected = new LinkedHashMap<>();
        for (PlaidItem item : itemRepository.findAllByUserIdOrderByCreatedAtAsc(userId)) {
            var response = plaidGateway.recurringTransactions(tokenCipher.decrypt(item.getAccessTokenEnc()));
            if (response.getOutflowStreams() == null) {
                continue;
            }
            response.getOutflowStreams().stream()
                .filter(this::isActive)
                .map(this::map)
                .filter(java.util.Objects::nonNull)
                .forEach(charge -> detected.merge(
                    charge.merchantKey(), charge, PlaidRecurringDetectionClient::mostRecent));
        }
        return List.copyOf(detected.values());
    }

    private DetectedCharge map(TransactionStream stream) {
        String merchant = firstPresent(stream.getMerchantName(), stream.getDescription());
        TransactionStreamAmount lastAmount = stream.getLastAmount();
        TransactionStreamAmount averageAmount = stream.getAverageAmount();
        Double last = amount(lastAmount);
        Double average = amount(averageAmount);
        Double selected = last == null ? average : last;
        if (merchant == null || selected == null || selected <= 0) {
            return null;
        }

        boolean variable = last != null && average != null && average != 0
            && Math.abs(last - average) / Math.abs(average) > 0.10;
        BigDecimal chargeAmount = BigDecimal.valueOf(variable ? average : selected)
            .setScale(2, RoundingMode.HALF_UP);
        PersonalFinanceCategory pfc = stream.getPersonalFinanceCategory();
        String primary = pfc == null ? null : pfc.getPrimary();
        String detailed = pfc == null ? null : pfc.getDetailed();
        TransactionStreamStatus status = stream.getStatus();

        return new DetectedCharge(
            normalizer.normalize(stream.getMerchantName(), stream.getDescription()),
            merchant,
            displayName(merchant),
            chargeAmount,
            null,
            currency(lastAmount == null ? averageAmount : lastAmount),
            billingCycle(stream.getFrequency()),
            stream.getPredictedNextDate(),
            confidence(status),
            detectionState(status),
            categoryRules.categorize(merchant, primary, detailed),
            "rule",
            null,
            variable,
            null,
            stream.getFirstDate(),
            stream.getLastDate(),
            null,
            null);
    }

    private boolean isActive(TransactionStream stream) {
        return !Boolean.FALSE.equals(stream.getIsActive())
            && stream.getStatus() != TransactionStreamStatus.TOMBSTONED;
    }

    private static DetectedCharge mostRecent(DetectedCharge left, DetectedCharge right) {
        LocalDate leftDate = left.lastSeenAt();
        LocalDate rightDate = right.lastSeenAt();
        if (leftDate == null) {
            return right;
        }
        return rightDate != null && rightDate.isAfter(leftDate) ? right : left;
    }

    private BillingCycle billingCycle(RecurringTransactionFrequency frequency) {
        if (frequency == null) {
            return null;
        }
        return switch (frequency) {
            case WEEKLY -> BillingCycle.WEEKLY;
            case BIWEEKLY -> BillingCycle.BIWEEKLY;
            case SEMI_MONTHLY -> BillingCycle.SEMI_MONTHLY;
            case MONTHLY -> BillingCycle.MONTHLY;
            case ANNUALLY -> BillingCycle.ANNUAL;
            default -> null;
        };
    }

    private String detectionState(TransactionStreamStatus status) {
        if (status == TransactionStreamStatus.MATURE) {
            return "confirmed";
        }
        return status == TransactionStreamStatus.EARLY_DETECTION ? "provisional" : "suspected";
    }

    private BigDecimal confidence(TransactionStreamStatus status) {
        if (status == TransactionStreamStatus.MATURE) {
            return MATURE_CONFIDENCE;
        }
        return status == TransactionStreamStatus.EARLY_DETECTION
            ? EARLY_CONFIDENCE
            : UNKNOWN_CONFIDENCE;
    }

    private Double amount(TransactionStreamAmount value) {
        return value == null ? null : value.getAmount();
    }

    private String currency(TransactionStreamAmount value) {
        if (value == null) {
            return "USD";
        }
        return firstPresent(value.getIsoCurrencyCode(), value.getUnofficialCurrencyCode(), "USD");
    }

    private String displayName(String merchant) {
        String clean = merchant.trim().replaceAll("\\s+", " ");
        if (!clean.equals(clean.toUpperCase(Locale.ROOT))) {
            return clean;
        }
        StringBuilder title = new StringBuilder();
        for (String word : clean.toLowerCase(Locale.ROOT).split(" ")) {
            if (!word.isBlank()) {
                if (!title.isEmpty()) {
                    title.append(' ');
                }
                title.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return title.toString();
    }

    private String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
