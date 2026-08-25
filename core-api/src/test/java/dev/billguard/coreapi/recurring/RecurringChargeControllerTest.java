package dev.billguard.coreapi.recurring;

import dev.billguard.coreapi.common.GlobalExceptionHandler;
import dev.billguard.coreapi.config.SecurityConfig;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RecurringChargeController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
    "AUTH0_DOMAIN=test.auth0.com",
    "AUTH0_AUDIENCE=https://api.billguard.test"
})
class RecurringChargeControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RecurringChargeService service;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void unsupportedCategoryReturnsValidationEnvelope() throws Exception {
        mockMvc.perform(post("/api/recurring-charges")
            .with(jwt().jwt(token -> token.subject("auth0|user-a")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "transaction_id": "cda50a51-b34f-4c43-8bc9-c1a4ff73d7fc",
                  "category": "video streaming"
                }
                """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Validation error"))
            .andExpect(jsonPath("$.details.body[0]").value("Invalid request body"));

        verifyNoInteractions(service);
    }
}
