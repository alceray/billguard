package dev.billguard.coreapi.health;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private static final Logger log = LoggerFactory.getLogger(HealthController.class);
    private final JdbcTemplate jdbcTemplate;

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/health")
    Map<String, String> health() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("timestamp", Instant.now().toString());
        return result;
    }

    @GetMapping("/ready")
    ResponseEntity<Map<String, String>> ready() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(Map.of("status", "ready"));
        } catch (RuntimeException exception) {
            log.warn("Database readiness check failed", exception);
            return ResponseEntity.status(503).body(Map.of(
                "status", "unavailable",
                "reason", "database"
            ));
        }
    }
}
