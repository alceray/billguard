package dev.billguard.coreapi.recurring;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recurring-charges")
public class RecurringChargeController {
    private final RecurringChargeService service;

    public RecurringChargeController(RecurringChargeService service) {
        this.service = service;
    }

    @GetMapping
    List<RecurringChargeResponse> list(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(required = false) String category
    ) {
        return service.list(jwt.getSubject(), category);
    }

    @GetMapping("/accuracy")
    AccuracyResponse accuracy(@AuthenticationPrincipal Jwt jwt) {
        return service.accuracy(jwt.getSubject());
    }

    @PostMapping("/detect")
    @ResponseStatus(HttpStatus.ACCEPTED)
    DetectionEnqueueResponse detect(@AuthenticationPrincipal Jwt jwt) {
        return service.enqueueDetection(jwt.getSubject());
    }

    @GetMapping("/detect/status")
    DetectionStatusResponse detectionStatus(@AuthenticationPrincipal Jwt jwt) {
        return service.detectionStatus(jwt.getSubject());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    RecurringChargeResponse promote(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody PromoteTransactionRequest request
    ) {
        return service.promote(jwt.getSubject(), request);
    }

    @DeleteMapping("/transactions/{transactionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unmarkTransaction(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID transactionId
    ) {
        service.unmarkTransaction(jwt.getSubject(), transactionId);
    }

    @PatchMapping("/{id}")
    RecurringChargeResponse update(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id,
        @Valid @RequestBody RecurringChargePatchRequest request
    ) {
        return service.update(jwt.getSubject(), id, request);
    }
}
