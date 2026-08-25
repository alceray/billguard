package dev.billguard.coreapi.detection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

@Component
public class RecurrenceDetector {
    private static final BigDecimal AMOUNT_CHANGE_EPSILON = new BigDecimal("0.01");
    // Above 10% coefficient of variation, use a trailing average and suppress price-change alerts.
    private static final double VARIABLE_AMOUNT_CV = 0.10;

    private final ChargeEligibility eligibility;
    private final CategoryRules categoryRules;

    public RecurrenceDetector(ChargeEligibility eligibility, CategoryRules categoryRules) {
        this.eligibility = eligibility;
        this.categoryRules = categoryRules;
    }

    public List<ChargeGroup> groupEligible(List<DetectionTransaction> transactions) {
        return transactions.stream()
            .filter(eligibility::isEligible)
            .filter(transaction -> transaction.merchantKey() != null && !transaction.merchantKey().isBlank())
            .collect(Collectors.groupingBy(
                DetectionTransaction::merchantKey,
                LinkedHashMap::new,
                Collectors.toList()))
            .entrySet().stream()
            .map(entry -> new ChargeGroup(entry.getKey(), entry.getValue()))
            .toList();
    }

    public List<DetectedCharge> detect(
        List<DetectionTransaction> transactions,
        Map<String, ModelClassification> modelClassifications
    ) {
        return detectGroups(groupEligible(transactions), modelClassifications);
    }

    public List<DetectedCharge> detectGroups(
        List<ChargeGroup> groups,
        Map<String, ModelClassification> modelClassifications
    ) {
        List<DetectedCharge> detected = new ArrayList<>();
        for (ChargeGroup group : groups) {
            DetectedCharge charge = detectGroup(group, modelClassifications.get(group.merchantKey()));
            if (charge != null) {
                detected.add(charge);
            }
        }
        return detected;
    }

    public RecurrenceMetrics metrics(ChargeGroup group) {
        List<Double> gaps = new ArrayList<>();
        for (int index = 1; index < group.transactions().size(); index++) {
            gaps.add((double) ChronoUnit.DAYS.between(
                group.transactions().get(index - 1).date(), group.transactions().get(index).date()));
        }
        double medianGap = median(gaps);
        BillingCycle cycle = gaps.isEmpty() ? null : BillingCycle.nearest(medianGap);
        double mad = gaps.isEmpty()
            ? 0
            : median(gaps.stream().map(gap -> Math.abs(gap - medianGap)).toList());
        double cv = coefficientOfVariation(group.transactions(), transaction -> transaction.amount().doubleValue());
        double regularity = cycle == null || gaps.isEmpty()
            ? 0
            : gaps.stream().filter(cycle::matches).count() / (double) gaps.size();
        return new RecurrenceMetrics(cycle, medianGap, mad, cv, regularity, cv > VARIABLE_AMOUNT_CV);
    }

    private DetectedCharge detectGroup(ChargeGroup group, ModelClassification model) {
        DetectionTransaction latest = group.latest();
        RecurringCategory ruleCategory = categoryRules.categorize(
            latest.merchantLabel(), latest.pfcPrimary(), latest.pfcDetailed());
        CategoryDecision category = category(ruleCategory, model);
        int count = group.transactions().size();
        RecurrenceMetrics metrics = metrics(group);

        boolean singletonSignal = count == 1 && (
            categoryRules.hasSingletonSignal(latest.merchantLabel(), latest.pfcPrimary(), latest.pfcDetailed())
                || model != null && model.recurringSignal());
        boolean recurringInterval = count >= 2 && metrics.cycle() != null && metrics.intervalRegularity() >= 0.67;
        if (!singletonSignal && !recurringInterval) {
            return null;
        }

        String state = count >= 3 && recurringInterval
            ? "confirmed"
            : count == 2 ? "provisional" : "suspected";
        BigDecimal previousTransactionAmount = count >= 2
            ? group.transactions().get(count - 2).amount()
            : null;
        boolean amountChanged = !metrics.variableAmount()
            && previousTransactionAmount != null
            && latest.amount().subtract(previousTransactionAmount).abs()
                .compareTo(AMOUNT_CHANGE_EPSILON) > 0;
        BigDecimal previous = amountChanged ? previousTransactionAmount : null;
        BigDecimal amount = metrics.variableAmount()
            ? averageAmount(group.transactions())
            : latest.amount();
        double intervalScore = metrics.cycle() == null
            ? 0.35
            : Math.max(0, 1 - metrics.medianAbsoluteDeviation() / Math.max(1, metrics.medianGapDays()));
        double countFactor = Math.max(0, Math.min(1, (count - 2) / 4.0));
        double amountScore = metrics.variableAmount()
            ? 1
            : Math.max(0, 1 - metrics.amountCoefficientOfVariation());
        double confidence = 0.4 * intervalScore + 0.3 * amountScore + 0.3 * countFactor;

        return new DetectedCharge(
            group.merchantKey(), latest.merchantLabel(), displayName(latest.merchantLabel(), model),
            amount.setScale(2, RoundingMode.HALF_UP), previous, latest.currency(), metrics.cycle(),
            metrics.cycle() == null ? null : latest.date().plusDays(Math.round(metrics.medianGapDays())),
            BigDecimal.valueOf(clamp(confidence)).setScale(2, RoundingMode.HALF_UP), state,
            category.value(), category.source(),
            amountChanged ? latest.date().atStartOfDay().toInstant(ZoneOffset.UTC) : null,
            metrics.variableAmount(), latest.id(), group.transactions().getFirst().date(), latest.date(),
            model == null ? null : model.promptVersion(), model == null ? null : model.reasoning());
    }

    private CategoryDecision category(
        RecurringCategory ruleCategory,
        ModelClassification model
    ) {
        if (ruleCategory != RecurringCategory.OTHER) {
            return new CategoryDecision(ruleCategory, "rule");
        }
        if (model != null && model.category() != RecurringCategory.OTHER) {
            return new CategoryDecision(model.category(), "model");
        }
        return new CategoryDecision(RecurringCategory.OTHER, "model");
    }

    private String displayName(String merchant, ModelClassification model) {
        if (model != null && model.displayName() != null && !model.displayName().isBlank()) {
            return model.displayName().trim();
        }
        String clean = merchant == null ? "Recurring charge" : merchant.trim().replaceAll("\\s+", " ");
        if (clean.equals(clean.toUpperCase(Locale.ROOT))) {
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
        return clean;
    }

    private BigDecimal averageAmount(List<DetectionTransaction> transactions) {
        return transactions.stream().map(DetectionTransaction::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(transactions.size()), 2, RoundingMode.HALF_UP);
    }

    private double coefficientOfVariation(
        List<DetectionTransaction> transactions,
        ToDoubleFunction<DetectionTransaction> extractor
    ) {
        double mean = transactions.stream().mapToDouble(extractor).average().orElse(0);
        if (mean == 0 || transactions.size() < 2) {
            return 0;
        }
        double variance = transactions.stream()
            .mapToDouble(transaction -> Math.pow(extractor.applyAsDouble(transaction) - mean, 2))
            .average().orElse(0);
        return Math.sqrt(variance) / Math.abs(mean);
    }

    private double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Double> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0
            ? (sorted.get(middle - 1) + sorted.get(middle)) / 2
            : sorted.get(middle);
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private record CategoryDecision(RecurringCategory value, String source) {
    }
}
