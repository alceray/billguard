package dev.billguard.coreapi.plaid;

import dev.billguard.coreapi.config.TokenCipher;
import dev.billguard.coreapi.jobs.JobQueueService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PlaidSyncService {
    private static final Logger log = LoggerFactory.getLogger(PlaidSyncService.class);
    private static final String LOGIN_REQUIRED = "ITEM_LOGIN_REQUIRED";
    private static final String MUTATION_DURING_PAGINATION = "TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION";

    private final PlaidItemRepository plaidItemRepository;
    private final PlaidGateway plaidGateway;
    private final TokenCipher tokenCipher;
    private final PlaidSyncPageWriter pageWriter;
    private final JobQueueService jobQueueService;

    public PlaidSyncService(
        PlaidItemRepository plaidItemRepository,
        PlaidGateway plaidGateway,
        TokenCipher tokenCipher,
        PlaidSyncPageWriter pageWriter,
        JobQueueService jobQueueService
    ) {
        this.plaidItemRepository = plaidItemRepository;
        this.plaidGateway = plaidGateway;
        this.tokenCipher = tokenCipher;
        this.pageWriter = pageWriter;
        this.jobQueueService = jobQueueService;
    }

    public void syncItem(String plaidItemId) {
        PlaidItem item = plaidItemRepository.findByPlaidItemId(plaidItemId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown Plaid item"));
        String accessToken = tokenCipher.decrypt(item.getAccessTokenEnc());
        String initialCursor = item.getCursor();
        String cursor = initialCursor;
        int mutationRetries = 0;

        while (true) {
            try {
                boolean hasMore;
                do {
                    var page = plaidGateway.syncTransactions(accessToken, cursor);
                    pageWriter.applyPage(plaidItemId, page);
                    cursor = page.getNextCursor();
                    hasMore = Boolean.TRUE.equals(page.getHasMore());
                } while (hasMore);
                jobQueueService.enqueueDetection(item.getUserId());
                log.info("Plaid item synced itemId={}", plaidItemId);
                return;
            } catch (PlaidGatewayException exception) {
                if (LOGIN_REQUIRED.equals(exception.code())) {
                    pageWriter.markItemLoginRequired(plaidItemId, exception.code());
                    log.warn("Plaid item requires login itemId={}", plaidItemId);
                    return;
                }
                if (MUTATION_DURING_PAGINATION.equals(exception.code()) && mutationRetries++ < 2) {
                    pageWriter.resetCursor(plaidItemId, initialCursor);
                    cursor = initialCursor;
                    log.info("Restarting Plaid pagination after mutation itemId={}", plaidItemId);
                    continue;
                }
                throw exception;
            }
        }
    }
}
