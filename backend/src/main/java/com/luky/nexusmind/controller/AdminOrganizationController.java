package com.luky.nexusmind.controller;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.model.SmtpSettings;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.SmtpSettingsRepository;
import com.luky.nexusmind.repository.UserRepository;
import com.luky.nexusmind.service.*;
import com.luky.nexusmind.utils.JwtUtils;
import com.luky.nexusmind.utils.PasswordUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/organization-management")
public class AdminOrganizationController {
    private final OrganizationService organizationService;
    private final AuditService auditService;
    private final SmtpSettingsRepository smtpRepository;
    private final SmtpCryptoService smtpCrypto;
    private final MailService mailService;
    private final UserRepository userRepository;
    private final JwtUtils jwtUtils;
    private final AccountStatusService accountStatusService;
    private final StringRedisTemplate redis;

    public AdminOrganizationController(OrganizationService organizationService, AuditService auditService,
                                       SmtpSettingsRepository smtpRepository, SmtpCryptoService smtpCrypto,
                                       MailService mailService, UserRepository userRepository, JwtUtils jwtUtils,
                                       AccountStatusService accountStatusService, StringRedisTemplate redis) {
        this.organizationService = organizationService;
        this.auditService = auditService;
        this.smtpRepository = smtpRepository;
        this.smtpCrypto = smtpCrypto;
        this.mailService = mailService;
        this.userRepository = userRepository;
        this.jwtUtils = jwtUtils;
        this.accountStatusService = accountStatusService;
        this.redis = redis;
    }

    @GetMapping("/requests")
    public ResponseEntity<?> requests(@RequestHeader("Authorization") String token,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        requireAdmin(token);
        return ok(organizationService.adminRequests(status, page, size));
    }

    @PostMapping("/requests/{id}/decision")
    public ResponseEntity<?> decide(@RequestHeader("Authorization") String token, @PathVariable Long id,
                                    @RequestBody DecisionRequest request, HttpServletRequest http) {
        User admin = requireAdmin(token);
        organizationService.decide(admin.getUsername(), id, request.approve(), request.reason(), ip(http));
        return message("申请已处理");
    }

    @PostMapping("/organizations/{tagId}/archive")
    public ResponseEntity<?> archive(@RequestHeader("Authorization") String token, @PathVariable String tagId,
                                     @RequestBody ReasonRequest request, HttpServletRequest http) {
        User admin = requireAdmin(token);
        organizationService.archive(admin.getUsername(), tagId, request.reason(), false, ip(http));
        return message("组织已归档");
    }

    @PostMapping("/organizations/{tagId}/restore")
    public ResponseEntity<?> restore(@RequestHeader("Authorization") String token, @PathVariable String tagId,
                                     @RequestBody ReasonRequest request, HttpServletRequest http) {
        User admin = requireAdmin(token);
        organizationService.archive(admin.getUsername(), tagId, request.reason(), true, ip(http));
        return message("组织已恢复");
    }

    @PutMapping("/organizations/{tagId}/joinable")
    public ResponseEntity<?> joinable(@RequestHeader("Authorization") String token, @PathVariable String tagId,
                                      @RequestBody JoinableRequest request, HttpServletRequest http) {
        User admin = requireAdmin(token);
        organizationService.setJoinable(admin.getUsername(), tagId, request.joinable(), ip(http));
        return message("申请入口已更新");
    }

    @PutMapping("/users/{userId}/memberships")
    public ResponseEntity<?> memberships(@RequestHeader("Authorization") String token, @PathVariable Long userId,
                                         @RequestBody MembershipRequest request, HttpServletRequest http) {
        User admin = requireAdmin(token);
        User target = userRepository.findById(userId).orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
        if (request.orgTags().contains("admin") || target.getRole().isAdministrator())
            verifyPassword(admin, request.currentPassword(), ip(http), userId);
        organizationService.assign(admin.getUsername(), userId, request.orgTags(), request.reason(), ip(http));
        return message("组织成员关系已更新");
    }

    @PostMapping("/users/{userId}/super-role")
    public ResponseEntity<?> superRole(@RequestHeader("Authorization") String token, @PathVariable Long userId,
                                       @RequestBody SuperRoleRequest request, HttpServletRequest http) {
        User admin = requireSuper(token);
        verifyPassword(admin, request.currentPassword(), ip(http), userId);
        organizationService.changeSuperRole(admin.getUsername(), userId, request.promote(), request.reason(), ip(http));
        return message("角色已更新");
    }

    @PutMapping("/users/{userId}/enabled")
    public ResponseEntity<?> accountEnabled(@RequestHeader("Authorization") String token, @PathVariable Long userId,
                                            @RequestBody AccountStatusRequest request, HttpServletRequest http) {
        User admin = requireSuper(token);
        verifyPassword(admin, request.currentPassword(), ip(http), userId);
        accountStatusService.change(admin, userId, request.enabled(), request.reason(), ip(http));
        return message(request.enabled() ? "账户已启用" : "账户已禁用");
    }

    @GetMapping("/audit")
    public ResponseEntity<?> audit(@RequestHeader("Authorization") String token,
                                   @RequestParam(required = false) Long userId,
                                   @RequestParam(required = false) String orgTag,
                                   @RequestParam(defaultValue = "1") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        requireAdmin(token);
        return ok(auditService.list(userId, orgTag, Math.max(page - 1, 0), size));
    }

    @GetMapping("/smtp")
    public ResponseEntity<?> smtp(@RequestHeader("Authorization") String token) {
        requireSuper(token);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("provider", mailService.provider());
        value.put("configured", mailService.isConfigured());
        value.put("enabled", mailService.isEnabled());
        if (mailService.usesTencentSes()) {
            value.put("region", mailService.region());
            value.put("fromAddress", mailService.fromAddress());
            return ok(value);
        }
        smtpRepository.findById(1L).ifPresent(settings -> {
            value.put("host", settings.getHost()); value.put("port", settings.getPort());
            value.put("username", settings.getUsername()); value.put("fromAddress", settings.getFromAddress());
            value.put("sslEnabled", settings.isSslEnabled()); value.put("enabled", settings.isEnabled());
            value.put("passwordConfigured", settings.getEncryptedPassword() != null && !settings.getEncryptedPassword().isBlank());
        });
        value.put("cryptoConfigured", smtpCrypto.configured());
        return ok(value);
    }

    @PutMapping("/smtp")
    public ResponseEntity<?> saveSmtp(@RequestHeader("Authorization") String token, @RequestBody SmtpRequest request,
                                     HttpServletRequest http) {
        User admin = requireSuper(token);
        if (mailService.usesTencentSes())
            throw new CustomException("腾讯云 SES 请通过部署环境变量配置", HttpStatus.BAD_REQUEST);
        verifyPassword(admin, request.currentPassword(), ip(http), null);
        SmtpSettings settings = smtpRepository.findById(1L).orElseGet(SmtpSettings::new);
        settings.setHost(request.host().trim()); settings.setPort(request.port()); settings.setUsername(request.username().trim());
        settings.setFromAddress(request.fromAddress().trim()); settings.setSslEnabled(request.sslEnabled()); settings.setEnabled(request.enabled());
        if (request.password() != null && !request.password().isBlank()) settings.setEncryptedPassword(smtpCrypto.encrypt(request.password()));
        if (settings.getEncryptedPassword() == null || settings.getEncryptedPassword().isBlank())
            throw new CustomException("请输入 SMTP 授权码", HttpStatus.BAD_REQUEST);
        smtpRepository.save(settings);
        auditService.record(admin, "SMTP_CONFIG_UPDATED", null, null, "更新邮件服务配置", ip(http));
        return message("邮件服务配置已保存");
    }

    @PutMapping("/smtp/enabled")
    public ResponseEntity<?> setMailEnabled(@RequestHeader("Authorization") String token,
                                            @RequestBody MailEnabledRequest request, HttpServletRequest http) {
        User admin = requireSuper(token);
        mailService.setEnabled(request.enabled());
        auditService.record(admin, "SMTP_CONFIG_UPDATED", null, null,
                request.enabled() ? "启用邮件服务" : "停用邮件服务", ip(http));
        return message(request.enabled() ? "邮件服务已启用" : "邮件服务已停用");
    }

    @PostMapping("/smtp/test")
    public ResponseEntity<?> testSmtp(@RequestHeader("Authorization") String token, @RequestBody TestMailRequest request) {
        requireSuper(token);
        mailService.sendTest(request.email());
        return message("测试邮件已发送");
    }

    private User requireAdmin(String token) {
        User user = current(token);
        if (!user.getRole().isAdministrator()) throw new CustomException("需要管理员权限", HttpStatus.FORBIDDEN);
        return user;
    }
    private User requireSuper(String token) {
        User user = current(token);
        if (user.getRole() != User.Role.SUPER_ADMIN) throw new CustomException("需要超级管理员权限", HttpStatus.FORBIDDEN);
        return user;
    }
    private User current(String token) { return userRepository.findByUsername(jwtUtils.extractUsernameFromToken(token.replace("Bearer ", "")))
            .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND)); }
    private void verifyPassword(User user, String password, String ip, Long targetUserId) {
        String key = "security:reauth:" + user.getId();
        String current = redis.opsForValue().get(key);
        if (current != null && Long.parseLong(current) >= 5) {
            throw new CustomException("密码验证尝试过多，请 10 分钟后重试", HttpStatus.TOO_MANY_REQUESTS);
        }
        if (password != null && PasswordUtil.matches(password, user.getPassword())) {
            redis.delete(key);
            return;
        }
        Long failures = redis.opsForValue().increment(key);
        if (failures != null && failures == 1) redis.expire(key, Duration.ofMinutes(10));
        if (failures != null && failures >= 5) {
            auditService.record(user, "SENSITIVE_REAUTH_RATE_LIMITED", targetUserId, null,
                    "当前密码连续验证失败", ip);
            throw new CustomException("密码验证尝试过多，请 10 分钟后重试", HttpStatus.TOO_MANY_REQUESTS);
        }
        throw new CustomException("当前密码错误", HttpStatus.FORBIDDEN);
    }
    private String ip(HttpServletRequest request) { String value = request.getHeader("X-Forwarded-For"); return value == null ? request.getRemoteAddr() : value.split(",")[0].trim(); }
    private ResponseEntity<?> ok(Object data) { return ResponseEntity.ok(Map.of("code", 200, "message", "成功", "data", data)); }
    private ResponseEntity<?> message(String value) { return ResponseEntity.ok(Map.of("code", 200, "message", value)); }
}

record DecisionRequest(boolean approve, String reason) {}
record SmtpRequest(String host, int port, String username, String password, String fromAddress,
                   boolean sslEnabled, boolean enabled, String currentPassword) {}
record MailEnabledRequest(boolean enabled) {}
record TestMailRequest(String email) {}
record MembershipRequest(java.util.List<String> orgTags, String reason, String currentPassword) {}
record SuperRoleRequest(boolean promote, String reason, String currentPassword) {}
record AccountStatusRequest(boolean enabled, String reason, String currentPassword) {}
record JoinableRequest(boolean joinable) {}
