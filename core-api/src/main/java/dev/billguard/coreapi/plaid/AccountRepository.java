package dev.billguard.coreapi.plaid;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    List<Account> findAllByUserIdOrderByNameAsc(UUID userId);
}
