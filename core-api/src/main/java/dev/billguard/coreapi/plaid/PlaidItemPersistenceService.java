package dev.billguard.coreapi.plaid;

import java.util.List;
import java.util.UUID;

import com.plaid.client.model.AccountBase;
import dev.billguard.coreapi.jobs.JobQueueService;
import dev.billguard.coreapi.plaid.dto.AccountResponse;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlaidItemPersistenceService {
    private final PlaidDataRepository dataRepository;
    private final AccountRepository accountRepository;
    private final JobQueueService jobQueueService;

    public PlaidItemPersistenceService(
        PlaidDataRepository dataRepository,
        AccountRepository accountRepository,
        JobQueueService jobQueueService
    ) {
        this.dataRepository = dataRepository;
        this.accountRepository = accountRepository;
        this.jobQueueService = jobQueueService;
    }

    @Transactional
    public List<AccountResponse> persistExchange(
        UUID userId,
        String plaidItemId,
        String encryptedAccessToken,
        String institutionId,
        String institutionName,
        List<AccountBase> accounts
    ) {
        dataRepository.upsertItem(
            userId, plaidItemId, encryptedAccessToken, institutionId, institutionName);
        for (AccountBase account : accounts) {
            dataRepository.upsertAccount(userId, plaidItemId, account);
        }
        jobQueueService.enqueueSync(plaidItemId);
        return accountRepository.findAllByUserIdOrderByNameAsc(userId).stream()
            .filter(account -> plaidItemId.equals(account.getPlaidItemId()))
            .map(AccountResponse::from)
            .toList();
    }
}
