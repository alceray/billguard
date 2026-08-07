package dev.billguard.coreapi.auth;

import java.time.Instant;
import java.util.UUID;

import dev.billguard.coreapi.auth.dto.UserResponse;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
    "AUTH0_DOMAIN=test.auth0.com",
    "AUTH0_AUDIENCE=https://api.billguard.test"
})
class AuthControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void rejectsMissingTokenWithCompatibleEnvelope() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Invalid or missing token"));
    }

    @Test
    void upsertsAuthenticatedUser() throws Exception {
        UserResponse response = new UserResponse(
            UUID.randomUUID(), "ray@example.com", "Ray", Instant.parse("2026-08-07T00:00:00Z"));
        when(userService.upsert(eq("auth0|ray"), any())).thenReturn(response);

        mockMvc.perform(post("/api/auth/me")
                .with(jwt().jwt(token -> token.subject("auth0|ray")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"ray@example.com\",\"name\":\"Ray\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("ray@example.com"))
            .andExpect(jsonPath("$.created_at").value("2026-08-07T00:00:00Z"));
    }

    @Test
    void malformedEmailReturnsValidationEnvelopeWithoutCallingService() throws Exception {
        mockMvc.perform(post("/api/auth/me")
                .with(jwt().jwt(token -> token.subject("auth0|ray")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"not-an-email\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Validation error"))
            .andExpect(jsonPath("$.details.email").isArray());
    }

    @Test
    void absentUserReturnsNotFoundEnvelope() throws Exception {
        when(userService.findBySubject("auth0|missing"))
            .thenThrow(new HttpException(HttpStatus.NOT_FOUND, "User not found"));

        mockMvc.perform(get("/api/auth/me")
                .with(jwt().jwt(token -> token.subject("auth0|missing"))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("User not found"));
    }

    @Test
    void unknownRouteReturnsCompatibleNotFoundEnvelope() throws Exception {
        mockMvc.perform(get("/not-a-route"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Not found"));
    }
}
