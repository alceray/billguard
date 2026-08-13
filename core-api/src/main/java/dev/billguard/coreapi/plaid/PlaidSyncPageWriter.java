package dev.billguard.coreapi.plaid;

import com.plaid.client.model.TransactionsSyncResponse;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlaidSyncPageWriter {
    private final PlaidDataRepository dataRepository;

    public PlaidSyncPageWriter(PlaidDataRepository dataRepository) {
        this.dataRepository = dataRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyPage(String plaidItemId, TransactionsSyncResponse page) {
        if (page.getAdded() != null) {
            page.getAdded().forEach(transaction -> dataRepository.applyTransaction(plaidItemId, transaction));
        }
        if (page.getModified() != null) {
            page.getModified().forEach(transaction -> dataRepository.applyTransaction(plaidItemId, transaction));
        }
        if (page.getRemoved() != null) {
            page.getRemoved().forEach(transaction -> dataRepository.removeTransaction(plaidItemId, transaction));
        }
        dataRepository.advanceCursor(plaidItemId, page.getNextCursor());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resetCursor(String plaidItemId, String cursor) {
        dataRepository.resetCursor(plaidItemId, cursor);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markItemLoginRequired(String plaidItemId, String errorCode) {
        dataRepository.markItemError(plaidItemId, "item_login_required", errorCode);
    }
}
