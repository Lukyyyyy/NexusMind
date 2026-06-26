package com.luky.nexusmind.controller;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.service.ModelConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/model-config")
public class ModelConfigController {
    private final ModelConfigService modelConfigService;

    public ModelConfigController(ModelConfigService modelConfigService) {
        this.modelConfigService = modelConfigService;
    }

    @GetMapping
    public ResponseEntity<?> list(Authentication authentication) {
        return ok(modelConfigService.listVisibleConfigs(username(authentication)));
    }

    @PostMapping
    public ResponseEntity<?> create(Authentication authentication,
                                    @RequestBody ModelConfigService.ModelConfigRequest request) {
        return ok(modelConfigService.createConfig(username(authentication), request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(Authentication authentication,
                                    @PathVariable Long id,
                                    @RequestBody ModelConfigService.ModelConfigRequest request) {
        return ok(modelConfigService.updateConfig(username(authentication), id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(Authentication authentication, @PathVariable Long id) {
        modelConfigService.deleteConfig(username(authentication), id);
        return ok(null);
    }

    @PutMapping("/preference")
    public ResponseEntity<?> updatePreference(Authentication authentication,
                                              @RequestBody ModelConfigService.PreferenceRequest request) {
        return ok(modelConfigService.updatePreference(username(authentication), request));
    }

    private ResponseEntity<?> ok(Object data) {
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", data == null ? Map.of() : data));
    }

    private String username(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new CustomException("Unauthorized", HttpStatus.UNAUTHORIZED);
        }
        return authentication.getName();
    }

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<?> handleCustomException(CustomException exception) {
        return ResponseEntity.status(exception.getStatus()).body(Map.of(
                "code", exception.getStatus().value(),
                "message", exception.getMessage(),
                "data", Map.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleException(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "code", 500,
                "message", exception.getMessage(),
                "data", Map.of()));
    }
}
