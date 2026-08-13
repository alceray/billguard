package dev.billguard.coreapi.plaid;

import dev.billguard.coreapi.common.GlobalExceptionHandler;
import dev.billguard.coreapi.common.HttpException;
import dev.billguard.coreapi.config.SecurityConfig;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlaidWebhookController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
    "AUTH0_DOMAIN=test.auth0.com",
    "AUTH0_AUDIENCE=https://api.billguard.test"
})
class PlaidWebhookControllerTest {
    private static final String BODY = "{\"webhook_type\":\"TRANSACTIONS\",\"webhook_code\":\"SYNC_UPDATES_AVAILABLE\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PlaidWebhookService webhookService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void unauthenticatedRequestReachesSignatureAuthenticatedController() throws Exception {
        mockMvc.perform(post("/api/webhooks/plaid")
                .header("Plaid-Verification", "signed-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isOk());

        verify(webhookService).handle("signed-jwt", BODY);
    }

    @Test
    void invalidSignatureUsesAStableUnauthorizedEnvelope() throws Exception {
        doThrow(new HttpException(
            HttpStatus.UNAUTHORIZED, "Invalid Plaid webhook", "INVALID_WEBHOOK_SIGNATURE"))
            .when(webhookService).handle("invalid", BODY);

        mockMvc.perform(post("/api/webhooks/plaid")
                .header("Plaid-Verification", "invalid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Invalid Plaid webhook"))
            .andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_SIGNATURE"));
    }
}
