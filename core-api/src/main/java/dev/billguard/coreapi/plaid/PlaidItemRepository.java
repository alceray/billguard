package dev.billguard.coreapi.plaid;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PlaidItemRepository extends JpaRepository<PlaidItem, UUID> {
    Optional<PlaidItem> findByPlaidItemId(String plaidItemId);

    Optional<PlaidItem> findByPlaidItemIdAndUserId(String plaidItemId, UUID userId);

    Optional<PlaidItem> findByIdAndUserId(UUID id, UUID userId);

    List<PlaidItem> findAllByUserIdOrderByCreatedAtAsc(UUID userId);

    @Query("select item.plaidItemId from PlaidItem item")
    List<String> findAllPlaidItemIds();
}
