package dev.billguard.coreapi.plaid;

import dev.billguard.coreapi.plaid.dto.TransactionPageResponse;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {
    private final PlaidReadService readService;

    public TransactionController(PlaidReadService readService) {
        this.readService = readService;
    }

    @GetMapping
    TransactionPageResponse transactions(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "50") int limit
    ) {
        return readService.transactions(jwt.getSubject(), cursor, limit);
    }
}
