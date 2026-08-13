package dev.billguard.coreapi.plaid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/webhooks")
public class PlaidWebhookController {
    private final PlaidWebhookService webhookService;

    public PlaidWebhookController(PlaidWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/plaid")
    ResponseEntity<Void> receive(
        @RequestHeader(name = "Plaid-Verification", required = false) String verificationHeader,
        @RequestBody String rawBody
    ) {
        webhookService.handle(verificationHeader, rawBody);
        return ResponseEntity.ok().build();
    }
}
