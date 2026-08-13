package dev.billguard.coreapi.plaid;

import dev.billguard.coreapi.jobs.JobQueueService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlaidWebhookPersistenceService {
    private final PlaidDataRepository dataRepository;
    private final JobQueueService jobQueueService;

    public PlaidWebhookPersistenceService(
        PlaidDataRepository dataRepository,
        JobQueueService jobQueueService
    ) {
        this.dataRepository = dataRepository;
        this.jobQueueService = jobQueueService;
    }

    @Transactional
    public void persist(
        String eventId,
        String webhookType,
        String webhookCode,
        String itemId,
        String errorCode,
        String rawBody
    ) {
        // Body-hash dedupe is an optimization. Cursor + Plaid transaction ID uniqueness
        // remains the correctness guarantee for duplicate and out-of-order deliveries.
        if (!dataRepository.recordWebhook(eventId, webhookType, webhookCode, itemId, rawBody)) {
            return;
        }
        if ("TRANSACTIONS".equals(webhookType) && "SYNC_UPDATES_AVAILABLE".equals(webhookCode)
            && itemId != null) {
            jobQueueService.enqueueSync(itemId);
        }
        if ("ITEM".equals(webhookType) && "ERROR".equals(webhookCode) && itemId != null) {
            String status = "ITEM_LOGIN_REQUIRED".equals(errorCode) ? "item_login_required" : "error";
            dataRepository.markItemError(itemId, status, errorCode == null ? "ITEM_ERROR" : errorCode);
        }
    }
}
