package com.luky.nexusmind.controller;

import com.luky.nexusmind.service.LangfuseObservabilityService;
import com.luky.nexusmind.service.LangfuseObservabilityService.LangfuseObservabilityException;
import com.luky.nexusmind.utils.JwtUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/observability/langfuse")
public class LangfuseObservabilityController {

    private final LangfuseObservabilityService observabilityService;
    private final JwtUtils jwtUtils;

    public LangfuseObservabilityController(LangfuseObservabilityService observabilityService, JwtUtils jwtUtils) {
        this.observabilityService = observabilityService;
        this.jwtUtils = jwtUtils;
    }

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview(@RequestParam String from,
                                                        @RequestParam String to,
                                                        @RequestHeader(value = "Authorization", required = false) String authorization,
                                                        Authentication authentication) {
        try {
            var data = observabilityService.getOverview(currentUser(authorization, authentication), Instant.parse(from), Instant.parse(to));
            return ok(data);
        } catch (LangfuseObservabilityException e) {
            return error(e);
        }
    }

    @GetMapping("/traces")
    public ResponseEntity<Map<String, Object>> traces(@RequestParam String from,
                                                      @RequestParam String to,
                                                      @RequestParam(required = false) String level,
                                                      @RequestParam(required = false) String traceName,
                                                      @RequestParam(required = false) String cursor,
                                                      @RequestParam(defaultValue = "100") int limit,
                                                      @RequestHeader(value = "Authorization", required = false) String authorization,
                                                      Authentication authentication) {
        try {
            var data = observabilityService.getTraces(
                    currentUser(authorization, authentication), Instant.parse(from), Instant.parse(to), level, traceName, cursor, limit);
            return ok(data);
        } catch (LangfuseObservabilityException e) {
            return error(e);
        }
    }

    @GetMapping("/traces/{traceId}")
    public ResponseEntity<Map<String, Object>> traceDetail(@PathVariable String traceId,
                                                           @RequestParam String from,
                                                           @RequestParam String to,
                                                           @RequestHeader(value = "Authorization", required = false) String authorization,
                                                           Authentication authentication) {
        try {
            var data = observabilityService.getTraceDetail(
                    currentUser(authorization, authentication), traceId, Instant.parse(from), Instant.parse(to));
            return ok(data);
        } catch (LangfuseObservabilityException e) {
            return error(e);
        }
    }

    private String currentUser(String authorization, Authentication authentication) {
        String token = bearerToken(authorization);
        if (token != null) {
            String userId = jwtUtils.extractUserIdFromToken(token);
            if (userId != null && !userId.isBlank()) {
                return userId;
            }
        }
        return authentication == null ? "" : authentication.getName();
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring(7);
    }

    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", data));
    }

    private static ResponseEntity<Map<String, Object>> error(LangfuseObservabilityException e) {
        return ResponseEntity.status(e.getStatus())
                .body(Map.of("code", e.getStatus().value(), "message", e.getMessage(), "data", Map.of()));
    }
}
