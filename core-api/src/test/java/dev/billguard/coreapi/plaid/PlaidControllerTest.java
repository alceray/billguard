package dev.billguard.coreapi.plaid;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import dev.billguard.coreapi.common.GlobalExceptionHandler;
import dev.billguard.coreapi.config.SecurityConfig;
import dev.billguard.coreapi.plaid.dto.TransactionPageResponse;
import dev.billguard.coreapi.plaid.dto.TransactionResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({PlaidController.class, TransactionController.class})
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
    "AUTH0_DOMAIN=test.auth0.com",
    "AUTH0_AUDIENCE=https://api.billguard.test"
})
class PlaidControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PlaidLinkService linkService;

    @MockitoBean
    private PlaidItemService itemService;

    @MockitoBean
    private PlaidReadService readService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void plaidEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/plaid/items"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Invalid or missing token"));
    }

    @Test
    void transactionQueryUsesOnlyTheAuthenticatedSubject() throws Exception {
        UUID id = UUID.randomUUID();
        when(readService.transactions("auth0|user-a", null, 50)).thenReturn(
            new TransactionPageResponse(List.of(new TransactionResponse(
                id, "transaction-a", new BigDecimal("12.34"), "USD", "Merchant", "merchant",
                "Merchant", LocalDate.parse("2026-08-08"), null, false, "Checking", "Test Bank",
                true)), null));

        mockMvc.perform(get("/api/transactions")
            .with(jwt().jwt(token -> token.subject("auth0|user-a"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transactions[0].plaid_transaction_id").value("transaction-a"))
            .andExpect(jsonPath("$.transactions[0].recurring").value(true));

        verify(readService).transactions("auth0|user-a", null, 50);
    }

    @Test
    void disconnectUsesOnlyTheAuthenticatedSubject() throws Exception {
        UUID itemId = UUID.randomUUID();

        mockMvc.perform(delete("/api/plaid/items/{itemId}", itemId)
            .with(jwt().jwt(token -> token.subject("auth0|user-a"))))
            .andExpect(status().isNoContent());

        verify(itemService).disconnect("auth0|user-a", itemId);
    }
}
