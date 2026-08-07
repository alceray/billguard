package dev.billguard.coreapi.health;

import dev.billguard.coreapi.common.GlobalExceptionHandler;
import dev.billguard.coreapi.config.SecurityConfig;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
    "AUTH0_DOMAIN=test.auth0.com",
    "AUTH0_AUDIENCE=https://api.billguard.test"
})
class HealthControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.timestamp").isString());
    }

    @Test
    void readyReturnsUnavailableWhenDatabaseIsDown() throws Exception {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
            .thenThrow(new CannotGetJdbcConnectionException("offline"));

        mockMvc.perform(get("/ready"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value("unavailable"))
            .andExpect(jsonPath("$.reason").value("database"));
    }
}
