package dev.billguard.coreapi.detection;

import java.math.BigDecimal;
import java.util.Locale;

import org.springframework.stereotype.Component;

@Component
public class ChargeEligibility {
    public boolean isEligible(DetectionTransaction transaction) {
        if (transaction.pending() || transaction.amount().compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        String primary = normalized(transaction.pfcPrimary());
        String detailed = normalized(transaction.pfcDetailed());
        if (primary.startsWith("INCOME") || detailed.startsWith("INCOME")
            || primary.equals("LOAN_DISBURSEMENTS")) {
            return false;
        }
        if (detailed.contains("CREDIT_CARD_PAYMENT")
            || detailed.contains("PAYCHECK")
            || detailed.contains("REFUND")) {
            return false;
        }
        boolean transfer = primary.startsWith("TRANSFER") || detailed.startsWith("TRANSFER");
        return !(transaction.matchedOwnTransfer() && transfer);
    }

    /**
     * A point of sale does not bill again on its own, so one charge there is never a
     * recurring charge. Costs a real miss: a membership signup seen once waits for its
     * second charge.
     */
    public boolean allowsSingletonPromotion(String pfcPrimary, String pfcDetailed) {
        String primary = normalized(pfcPrimary);
        String detailed = normalized(pfcDetailed);
        for (String pointOfSale : POINT_OF_SALE_PRIMARIES) {
            if (primary.startsWith(pointOfSale)) {
                return false;
            }
        }
        return !detailed.contains("BOOKSTORES_AND_NEWSSTANDS");
    }

    private static final String[] POINT_OF_SALE_PRIMARIES = {
        "FOOD_AND_DRINK",
        "GENERAL_MERCHANDISE",
        "TRANSPORTATION",
        "TRAVEL",
        "PERSONAL_CARE",
        "HOME_IMPROVEMENT",
        "MEDICAL",
        "BANK_FEES",
    };

    private String normalized(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }
}
