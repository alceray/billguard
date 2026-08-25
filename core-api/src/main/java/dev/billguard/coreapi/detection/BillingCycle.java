package dev.billguard.coreapi.detection;

import java.util.Arrays;

public enum BillingCycle {
    WEEKLY("weekly", 7, 2),
    BIWEEKLY("biweekly", 14, 3),
    SEMI_MONTHLY("semi_monthly", 15, 2),
    MONTHLY("monthly", 30, 5),
    QUARTERLY("quarterly", 91, 10),
    ANNUAL("annual", 365, 20);

    private final String value;
    private final int days;
    private final int tolerance;

    BillingCycle(String value, int days, int tolerance) {
        this.value = value;
        this.days = days;
        this.tolerance = tolerance;
    }

    public String value() {
        return value;
    }

    public int days() {
        return days;
    }

    public int tolerance() {
        return tolerance;
    }

    public boolean matches(double gapDays) {
        return Math.abs(gapDays - days) <= tolerance;
    }

    public static BillingCycle nearest(double medianGap) {
        return Arrays.stream(values())
            .filter(cycle -> cycle.matches(medianGap))
            .min((left, right) -> Double.compare(
                Math.abs(left.days - medianGap), Math.abs(right.days - medianGap)))
            .orElse(null);
    }
}
