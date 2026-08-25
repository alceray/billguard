package dev.billguard.coreapi.recurring;

import java.util.List;
import java.util.UUID;

import dev.billguard.coreapi.auth.User;
import dev.billguard.coreapi.auth.UserRepository;
import dev.billguard.coreapi.common.HttpException;
import dev.billguard.coreapi.jobs.JobQueueService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecurringChargeService {
    private final UserRepository userRepository;
    private final RecurringChargeRepository repository;
    private final JobQueueService jobQueueService;

    public RecurringChargeService(
        UserRepository userRepository,
        RecurringChargeRepository repository,
        JobQueueService jobQueueService
    ) {
        this.userRepository = userRepository;
        this.repository = repository;
        this.jobQueueService = jobQueueService;
    }

    @Transactional(readOnly = true)
    public List<RecurringChargeResponse> list(String subject, String category) {
        return repository.findAll(requireUser(subject).getId(), blankToNull(category));
    }

    @Transactional
    public RecurringChargeResponse update(String subject, UUID id, RecurringChargePatchRequest request) {
        if (request.empty()) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "At least one recurring-charge field is required");
        }
        UUID userId = requireUser(subject).getId();
        String category = request.category() == null ? null : request.category().value();
        if (!repository.update(userId, id, request.recurring(), category)) {
            throw new HttpException(HttpStatus.NOT_FOUND, "Recurring charge not found");
        }
        return repository.findById(userId, id);
    }

    @Transactional
    public RecurringChargeResponse promote(String subject, PromoteTransactionRequest request) {
        UUID userId = requireUser(subject).getId();
        String category = request.category() == null ? "other" : request.category().value();
        UUID id = repository.promoteTransaction(userId, request.transactionId(), category);
        if (id == null) {
            throw new HttpException(HttpStatus.NOT_FOUND, "Transaction not found or cannot be promoted");
        }
        return repository.findById(userId, id);
    }

    @Transactional
    public void unmarkTransaction(String subject, UUID transactionId) {
        UUID userId = requireUser(subject).getId();
        if (!repository.unmarkTransaction(userId, transactionId)) {
            throw new HttpException(HttpStatus.NOT_FOUND, "Recurring charge not found for transaction");
        }
    }

    @Transactional
    public DetectionEnqueueResponse enqueueDetection(String subject) {
        UUID userId = requireUser(subject).getId();
        return new DetectionEnqueueResponse(jobQueueService.enqueueDetection(userId));
    }

    @Transactional(readOnly = true)
    public DetectionStatusResponse detectionStatus(String subject) {
        UUID userId = requireUser(subject).getId();
        return new DetectionStatusResponse(jobQueueService.detectionStatus(userId));
    }

    @Transactional(readOnly = true)
    public AccuracyResponse accuracy(String subject) {
        return repository.accuracy(requireUser(subject).getId());
    }

    private User requireUser(String subject) {
        return userRepository.findByAuth0Sub(subject)
            .orElseThrow(() -> new HttpException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
