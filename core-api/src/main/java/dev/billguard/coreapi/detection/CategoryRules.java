package dev.billguard.coreapi.detection;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class CategoryRules {
    private static final Map<String, RecurringCategory> DETAILED_RULES = Map.ofEntries(
        Map.entry("rent and utilities rent", RecurringCategory.HOUSING),
        Map.entry("rent and utilities gas and electricity", RecurringCategory.UTILITIES),
        Map.entry("rent and utilities water", RecurringCategory.UTILITIES),
        Map.entry("rent and utilities sewage and waste management", RecurringCategory.UTILITIES),
        Map.entry("rent and utilities internet and cable", RecurringCategory.TELECOM),
        Map.entry("rent and utilities telephone", RecurringCategory.TELECOM),
        Map.entry("general services insurance", RecurringCategory.INSURANCE),
        Map.entry("entertainment tv and movies", RecurringCategory.STREAMING),
        Map.entry("entertainment music and audio", RecurringCategory.STREAMING),
        Map.entry("general services storage", RecurringCategory.STORAGE_AND_HOSTING));

    private static final List<MerchantRule> MERCHANT_RULES = List.of(
        new MerchantRule(RecurringCategory.HOUSING, "mortgage", "property management", "landlord"),
        new MerchantRule(RecurringCategory.LOAN, "student loan", "auto loan"),
        new MerchantRule(RecurringCategory.UTILITIES,
            "hydro utility", "electric utility", "water utility", "gas utility"),
        new MerchantRule(RecurringCategory.TELECOM, "broadband", "wireless"),
        new MerchantRule(RecurringCategory.INSURANCE, "insurance premium"),
        new MerchantRule(RecurringCategory.STREAMING, "netflix", "hulu", "spotify"),
        new MerchantRule(RecurringCategory.STORAGE_AND_HOSTING, "icloud", "dropbox", "cloud storage"),
        new MerchantRule(RecurringCategory.AI,
            "openai", "chatgpt", "anthropic", "claude", "midjourney"),
        new MerchantRule(RecurringCategory.SOFTWARE,
            "github", "adobe", "microsoft 365", "software", "saas"),
        new MerchantRule(RecurringCategory.GAMING, "playstation", "xbox", "nintendo", "gaming"),
        new MerchantRule(RecurringCategory.NEWS_AND_MEDIA,
            "newspaper", "news", "media subscription"),
        new MerchantRule(RecurringCategory.FITNESS_AND_HEALTH,
            "gym", "fitness", "peloton", "health club"),
        new MerchantRule(RecurringCategory.FOOD_AND_DELIVERY,
            "doordash", "uber eats", "meal kit", "food delivery"),
        new MerchantRule(RecurringCategory.SHOPPING_MEMBERSHIP,
            "costco", "amazon prime", "shopping membership"),
        new MerchantRule(RecurringCategory.EDUCATION, "tuition", "education", "course", "learning"),
        new MerchantRule(RecurringCategory.FINANCE,
            "credit monitoring", "financial service", "investing subscription"));

    public RecurringCategory categorize(String merchant, String ignoredPrimary, String detailed) {
        String merchantText = normalize(merchant);
        String detailedValue = normalize(detailed);
        RecurringCategory pfcCategory = DETAILED_RULES.get(detailedValue);
        if (pfcCategory != null) {
            return pfcCategory;
        }
        if ((detailedValue.startsWith("loan payments ")
            || detailedValue.startsWith("loan repayments "))
            && !detailedValue.contains("credit card payment")) {
            return RecurringCategory.LOAN;
        }
        return MERCHANT_RULES.stream()
            .filter(rule -> containsAny(merchantText, rule.terms()))
            .map(MerchantRule::category)
            .findFirst()
            .orElse(RecurringCategory.OTHER);
    }

    public boolean hasSingletonSignal(String merchant, String primary, String detailed) {
        return categorize(merchant, primary, detailed) != RecurringCategory.OTHER
            || containsAny(normalize(merchant), "subscription", "monthly", "annual");
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private record MerchantRule(RecurringCategory category, String... terms) {
    }
}
