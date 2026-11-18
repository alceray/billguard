package dev.billguard.coreapi.detection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.billguard.coreapi.detection.CachedClassificationService.ClassificationResolution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecurringChargeDetectionServiceTest {
    @Mock
    private RecurringChargeDetectionRepository repository;

    @Mock
    private CachedClassificationService classificationService;

    @Mock
    private PlaidRecurringDetectionClient plaidClient;

    @Mock
    private RecurringChargeWriter writer;

    private RecurringChargeDetectionService service;

    @BeforeEach
    void setUp() {
        ChargeEligibility eligibility = new ChargeEligibility();
        CategoryRules categoryRules = new CategoryRules();
        service = new RecurringChargeDetectionService(
            repository,
            new RecurrenceDetector(eligibility, categoryRules),
            classificationService,
            eligibility,
            categoryRules,
            plaidClient,
            writer);
        when(plaidClient.detect(any(UUID.class))).thenReturn(List.of());
        when(classificationService.classify(anyList())).thenReturn(
            new ClassificationResolution(Map.of(), 0, 0, 0, false));
    }

    @Test
    void detectionGroupsRemainCompleteWhenClassificationIsEmpty() {
        List<DetectionTransaction> transactions = new ArrayList<>();
        transactions.addAll(monthly("netflix", "NETFLIX.COM", 3, null, null));
        transactions.addAll(monthly(
            "rent", "CITY PROPERTY MANAGEMENT", 3,
            "RENT_AND_UTILITIES", "RENT_AND_UTILITIES_RENT"));
        transactions.addAll(monthly("unknown club", "UNKNOWN CLUB", 3, null, null));
        when(repository.findTransactions(any(UUID.class))).thenReturn(transactions);

        int detected = service.detectUser(UUID.randomUUID());

        assertThat(detected).isEqualTo(3);
        ArgumentCaptor<List<DetectedCharge>> charges = ArgumentCaptor.forClass(List.class);
        verify(writer).replaceAutomaticDetections(any(UUID.class), charges.capture());
        assertThat(charges.getValue()).extracting(DetectedCharge::merchantKey)
            .containsExactlyInAnyOrder("netflix", "rent", "unknown club");

        ArgumentCaptor<List<ClassificationCandidate>> candidates =
            ArgumentCaptor.forClass(List.class);
        verify(classificationService).classify(candidates.capture());
        assertThat(candidates.getValue()).extracting(ClassificationCandidate::merchantKey)
            .containsExactly("unknown club");
    }

    @Test
    void retailSingletonNeverReachesTheClassifier() {
        List<DetectionTransaction> transactions = List.of(
            transaction(
                "coffee", "CORNER COFFEE", LocalDate.of(2026, 8, 1),
                "FOOD_AND_DRINK", "FOOD_AND_DRINK_COFFEE"),
            transaction("unknown", "UNKNOWN SERVICE", LocalDate.of(2026, 8, 1), null, null));
        when(repository.findTransactions(any(UUID.class))).thenReturn(transactions);

        service.detectUser(UUID.randomUUID());

        ArgumentCaptor<List<ClassificationCandidate>> candidates =
            ArgumentCaptor.forClass(List.class);
        verify(classificationService).classify(candidates.capture());
        assertThat(candidates.getValue()).extracting(ClassificationCandidate::merchantKey)
            .containsExactly("unknown");
    }

    @Test
    void classificationCandidatesUseStablePriorityOrder() {
        List<DetectionTransaction> transactions = new ArrayList<>();
        transactions.addAll(monthly("three", "THREE", 3, null, null));
        transactions.addAll(monthly("zulu", "ZULU", 2, null, null));
        transactions.addAll(monthly("alpha", "ALPHA", 2, null, null));
        transactions.add(transaction("single", "SINGLE", LocalDate.of(2026, 8, 1), null, null));
        when(repository.findTransactions(any(UUID.class))).thenReturn(transactions);

        service.detectUser(UUID.randomUUID());

        ArgumentCaptor<List<ClassificationCandidate>> candidates =
            ArgumentCaptor.forClass(List.class);
        verify(classificationService).classify(candidates.capture());
        assertThat(candidates.getValue()).extracting(ClassificationCandidate::merchantKey)
            .containsExactly("three", "alpha", "zulu", "single");
    }

    private List<DetectionTransaction> monthly(
        String merchantKey,
        String merchant,
        int count,
        String pfcPrimary,
        String pfcDetailed
    ) {
        return java.util.stream.IntStream.range(0, count)
            .mapToObj(index -> transaction(
                merchantKey, merchant, LocalDate.of(2026, 6, 1).plusMonths(index),
                pfcPrimary, pfcDetailed))
            .toList();
    }

    private DetectionTransaction transaction(
        String merchantKey,
        String merchant,
        LocalDate date,
        String pfcPrimary,
        String pfcDetailed
    ) {
        return new DetectionTransaction(
            UUID.randomUUID(), UUID.randomUUID(), merchantKey, merchant, merchant,
            new BigDecimal("10.00"), "USD", date, false, pfcPrimary, pfcDetailed, false);
    }
}
