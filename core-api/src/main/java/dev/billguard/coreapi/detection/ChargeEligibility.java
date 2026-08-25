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

    private String normalized(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }
}
