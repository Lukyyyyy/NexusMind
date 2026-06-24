package com.luky.nexusmind.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luky.nexusmind.service.LangfuseObservabilityService;
import com.luky.nexusmind.utils.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LangfuseObservabilityControllerTest {

    @Test
    void overviewQueriesLangfuseWithJwtUserId() {
        CapturingObservabilityService service = new CapturingObservabilityService();
        JwtUtils jwtUtils = new FixedJwtUtils("42");
        LangfuseObservabilityController controller = new LangfuseObservabilityController(service, jwtUtils);
        String from = "2026-06-24T00:00:00Z";
        String to = "2026-06-24T01:00:00Z";

        ResponseEntity<Map<String, Object>> response = controller.overview(
                from,
                to,
                "Bearer jwt-token",
                new UsernamePasswordAuthenticationToken("admin", null));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("42", service.lastUserId);
    }

    private static class CapturingObservabilityService extends LangfuseObservabilityService {
        private String lastUserId;

        private CapturingObservabilityService() {
            super(false, "https://cloud.langfuse.com", "", "", new ObjectMapper());
        }

        @Override
        public OverviewResponse getOverview(String userId, Instant from, Instant to) {
            this.lastUserId = userId;
            return new OverviewResponse(true, null, 0, 0, 0, 0, 0, 0, List.of(), List.of());
        }
    }

    private static class FixedJwtUtils extends JwtUtils {
        private final String userId;

        private FixedJwtUtils(String userId) {
            this.userId = userId;
        }

        @Override
        public String extractUserIdFromToken(String token) {
            return userId;
        }
    }
}
