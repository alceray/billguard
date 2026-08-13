package dev.billguard.coreapi.plaid;

import java.util.List;

import dev.billguard.coreapi.plaid.dto.ExchangeRequest;
import dev.billguard.coreapi.plaid.dto.ExchangeResponse;
import dev.billguard.coreapi.plaid.dto.LinkTokenResponse;
import dev.billguard.coreapi.plaid.dto.PlaidItemResponse;
import dev.billguard.coreapi.plaid.dto.SyncEnqueueResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/plaid")
public class PlaidController {
    private final PlaidLinkService linkService;
    private final PlaidItemService itemService;
    private final PlaidReadService readService;

    public PlaidController(
        PlaidLinkService linkService,
        PlaidItemService itemService,
        PlaidReadService readService
    ) {
        this.linkService = linkService;
        this.itemService = itemService;
        this.readService = readService;
    }

    @PostMapping("/link-token")
    LinkTokenResponse createLinkToken(@AuthenticationPrincipal Jwt jwt) {
        return linkService.createLinkToken(jwt.getSubject());
    }

    @PostMapping("/exchange")
    ExchangeResponse exchange(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody ExchangeRequest request
    ) {
        return itemService.exchange(jwt.getSubject(), request);
    }

    @PostMapping("/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    SyncEnqueueResponse sync(@AuthenticationPrincipal Jwt jwt) {
        return readService.enqueueUserItems(jwt.getSubject());
    }

    @GetMapping("/items")
    List<PlaidItemResponse> items(@AuthenticationPrincipal Jwt jwt) {
        return readService.items(jwt.getSubject());
    }
}
