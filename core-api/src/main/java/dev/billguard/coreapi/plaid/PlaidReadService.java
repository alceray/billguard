package dev.billguard.coreapi.plaid;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import dev.billguard.coreapi.auth.User;
import dev.billguard.coreapi.auth.UserRepository;
import dev.billguard.coreapi.common.HttpException;
import dev.billguard.coreapi.jobs.JobQueueService;
import dev.billguard.coreapi.plaid.dto.AccountResponse;
import dev.billguard.coreapi.plaid.dto.PlaidItemResponse;
import dev.billguard.coreapi.plaid.dto.SyncEnqueueResponse;
import dev.billguard.coreapi.plaid.dto.TransactionPageResponse;
import dev.billguard.coreapi.plaid.dto.TransactionResponse;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlaidReadService {
    private final UserRepository userRepository;
    private final PlaidItemRepository itemRepository;
    private final AccountRepository accountRepository;
    private final PlaidDataRepository dataRepository;
    private final JobQueueService jobQueueService;

    public PlaidReadService(
        UserRepository userRepository,
        PlaidItemRepository itemRepository,
        AccountRepository accountRepository,
        PlaidDataRepository dataRepository,
        JobQueueService jobQueueService
    ) {
        this.userRepository = userRepository;
        this.itemRepository = itemRepository;
        this.accountRepository = accountRepository;
        this.dataRepository = dataRepository;
        this.jobQueueService = jobQueueService;
    }

    @Transactional(readOnly = true)
    public List<PlaidItemResponse> items(String subject) {
        User user = requireUser(subject);
        Map<String, List<AccountResponse>> accounts = accountRepository
            .findAllByUserIdOrderByNameAsc(user.getId()).stream()
            .collect(Collectors.groupingBy(
                Account::getPlaidItemId,
                Collectors.mapping(AccountResponse::from, Collectors.toList())));
        return itemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId()).stream()
            .map(item -> new PlaidItemResponse(
                item.getPlaidItemId(), item.getInstitutionId(), item.getInstitutionName(),
                item.getStatus(), item.getLastError(), item.getLastSyncedAt(),
                accounts.getOrDefault(item.getPlaidItemId(), List.of())))
            .toList();
    }

    @Transactional
    public SyncEnqueueResponse enqueueUserItems(String subject) {
        User user = requireUser(subject);
        int enqueued = 0;
        for (PlaidItem item : itemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId())) {
            if (jobQueueService.enqueueSync(item.getPlaidItemId())) {
                enqueued++;
            }
        }
        return new SyncEnqueueResponse(enqueued);
    }

    @Transactional(readOnly = true)
    public TransactionPageResponse transactions(String subject, String cursor, int limit) {
        if (limit < 1 || limit > 100) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Limit must be between 1 and 100");
        }
        User user = requireUser(subject);
        Cursor decoded = decodeCursor(cursor);
        List<TransactionResponse> fetched = dataRepository.findTransactions(
            user.getId(), decoded == null ? null : decoded.date(), decoded == null ? null : decoded.id(), limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<TransactionResponse> page = hasMore
            ? new ArrayList<>(fetched.subList(0, limit))
            : fetched;
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            TransactionResponse last = page.get(page.size() - 1);
            nextCursor = encodeCursor(last.date(), last.id());
        }
        return new TransactionPageResponse(page, nextCursor);
    }

    private User requireUser(String subject) {
        return userRepository.findByAuth0Sub(subject)
            .orElseThrow(() -> new HttpException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException();
            }
            return new Cursor(LocalDate.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException exception) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Invalid transaction cursor");
        }
    }

    private String encodeCursor(LocalDate date, UUID id) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString((date + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    private record Cursor(LocalDate date, UUID id) {
    }
}
