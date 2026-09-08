package com.luky.nexusmind.controller;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.model.AiModelType;
import com.luky.nexusmind.repository.UserRepository;
import com.luky.nexusmind.service.AuditService;
import com.luky.nexusmind.service.ModelUsageService;
import com.luky.nexusmind.utils.JwtUtils;
import com.luky.nexusmind.utils.PasswordUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ModelUsageController {
    private final ModelUsageService service;
    private final UserRepository users;
    private final JwtUtils jwtUtils;
    private final StringRedisTemplate redis;
    private final AuditService auditService;

    public ModelUsageController(ModelUsageService service, UserRepository users, JwtUtils jwtUtils,
                                StringRedisTemplate redis, AuditService auditService) {
        this.service = service; this.users = users; this.jwtUtils = jwtUtils; this.redis = redis; this.auditService = auditService;
    }

    @GetMapping("/model-usage/overview")
    public ResponseEntity<?> overview(@RequestHeader("Authorization") String token,
                                      @RequestParam(required = false) String month,
                                      @RequestParam(required = false) Long userId,
                                      @RequestParam(required = false) String modelName) {
        User viewer = current(token);
        YearMonth selected;
        try { selected = month == null || month.isBlank() ? YearMonth.now(ZoneId.of("Asia/Shanghai")) : YearMonth.parse(month); }
        catch (Exception e) { throw new CustomException("月份格式应为 YYYY-MM", HttpStatus.BAD_REQUEST); }
        if (viewer.getRole() != User.Role.SUPER_ADMIN && userId != null && !viewer.getId().equals(userId))
            throw new CustomException("无权查看其他用户的用量", HttpStatus.FORBIDDEN);
        return ok(service.overview(viewer, selected, userId, modelName));
    }

    @PutMapping("/admin/model-usage/users/{userId}/quota")
    public ResponseEntity<?> quota(@RequestHeader("Authorization") String token, @PathVariable Long userId,
                                   @RequestBody QuotaRequest request, HttpServletRequest http) {
        User actor = requireSuper(token);
        verifyPassword(actor, request.currentPassword(), ip(http), userId);
        service.updateQuota(actor, userId, request.monthlyLimit(), request.monthlyReset(), request.reason(), ip(http));
        return message("用户额度已更新");
    }

    @PutMapping("/admin/model-usage/pricing/{ruleId}")
    public ResponseEntity<?> pricing(@RequestHeader("Authorization") String token, @PathVariable Long ruleId,
                                     @RequestBody PricingRequest request, HttpServletRequest http) {
        User actor = requireSuper(token);
        verifyPassword(actor, request.currentPassword(), ip(http), null);
        service.updatePricing(actor, ruleId, request.update(), request.reason(), ip(http));
        return message("计价规则已更新");
    }

    @PostMapping("/admin/model-usage/pricing")
    public ResponseEntity<?> createPricing(@RequestHeader("Authorization") String token,
                                           @RequestBody CreatePricingRequest request, HttpServletRequest http) {
        User actor = requireSuper(token);
        verifyPassword(actor, request.currentPassword(), ip(http), null);
        service.createPricing(actor, request.modelName(), request.modelType(), request.update(), request.reason(), ip(http));
        return message("计价规则已创建");
    }

    private User current(String token) {
        return users.findByUsername(jwtUtils.extractUsernameFromToken(token.replace("Bearer ", "")))
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
    }
    private User requireSuper(String token) {
        User user = current(token);
        if (user.getRole() != User.Role.SUPER_ADMIN) throw new CustomException("需要超级管理员权限", HttpStatus.FORBIDDEN);
        return user;
    }
    private void verifyPassword(User user, String password, String ip, Long targetUserId) {
        String key = "security:reauth:" + user.getId();
        String current = redis.opsForValue().get(key);
        if (current != null && Long.parseLong(current) >= 5)
            throw new CustomException("密码验证尝试过多，请 10 分钟后重试", HttpStatus.TOO_MANY_REQUESTS);
        if (password != null && PasswordUtil.matches(password, user.getPassword())) { redis.delete(key); return; }
        Long failures = redis.opsForValue().increment(key);
        if (failures != null && failures == 1) redis.expire(key, Duration.ofMinutes(10));
        if (failures != null && failures >= 5) {
            auditService.record(user, "SENSITIVE_REAUTH_RATE_LIMITED", targetUserId, null, "当前密码连续验证失败", ip);
            throw new CustomException("密码验证尝试过多，请 10 分钟后重试", HttpStatus.TOO_MANY_REQUESTS);
        }
        throw new CustomException("当前密码错误", HttpStatus.FORBIDDEN);
    }
    private String ip(HttpServletRequest request) { String value = request.getHeader("X-Forwarded-For"); return value == null ? request.getRemoteAddr() : value.split(",")[0].trim(); }
    private ResponseEntity<?> ok(Object data) { return ResponseEntity.ok(Map.of("code", 200, "message", "成功", "data", data)); }
    private ResponseEntity<?> message(String value) { return ResponseEntity.ok(Map.of("code", 200, "message", value)); }

    public record QuotaRequest(BigDecimal monthlyLimit, boolean monthlyReset, String currentPassword, String reason) {}
    public record PricingRequest(boolean enabled, BigDecimal inputPrice, BigDecimal cacheHitPrice, BigDecimal outputPrice,
                                 BigDecimal offPeakInputPrice, BigDecimal offPeakCacheHitPrice, BigDecimal offPeakOutputPrice,
                                 String currentPassword, String reason) {
        ModelUsageService.PricingUpdate update() { return new ModelUsageService.PricingUpdate(enabled, inputPrice,
                cacheHitPrice, outputPrice, offPeakInputPrice, offPeakCacheHitPrice, offPeakOutputPrice); }
    }
    public record CreatePricingRequest(String modelName, AiModelType modelType, boolean enabled,
                                       BigDecimal inputPrice, BigDecimal cacheHitPrice, BigDecimal outputPrice,
                                       BigDecimal offPeakInputPrice, BigDecimal offPeakCacheHitPrice, BigDecimal offPeakOutputPrice,
                                       String currentPassword, String reason) {
        ModelUsageService.PricingUpdate update() { return new ModelUsageService.PricingUpdate(enabled, inputPrice,
                cacheHitPrice, outputPrice, offPeakInputPrice, offPeakCacheHitPrice, offPeakOutputPrice); }
    }
}
