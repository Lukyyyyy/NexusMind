package com.luky.nexusmind.controller;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.UserRepository;
import com.luky.nexusmind.service.UserService;
import com.luky.nexusmind.service.EmailVerificationService;
import com.luky.nexusmind.utils.JwtUtils;
import com.luky.nexusmind.utils.LogUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailVerificationService emailVerificationService;

    @PostMapping("/registration-code")
    public ResponseEntity<?> registrationCode(@RequestBody RegistrationEmailRequest request) {
        emailVerificationService.requestRegistration(request.email());
        return ResponseEntity.ok(Map.of("code", 200, "message", "验证码已发送"));
    }

    @PostMapping("/password-reset-code")
    public ResponseEntity<?> passwordResetCode(@RequestBody RegistrationEmailRequest request) {
        emailVerificationService.requestPasswordReset(request.email());
        return ResponseEntity.ok(Map.of("code", 200, "message", "验证码已发送"));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody ResetPasswordRequest request) {
        Long userId = userService.resetPassword(request.email(), request.verificationCode(), request.password());
        jwtUtils.invalidateAllUserTokens(userId.toString());
        return ResponseEntity.ok(Map.of("code", 200, "message", "密码已重置，请重新登录"));
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("USER_REGISTER");
        try {
            User user = userService.registerVerifiedUser(request.email(), request.verificationCode(), request.password());
            LogUtils.logUserOperation(user.getUsername(), "REGISTER", "user_creation", "SUCCESS");
            monitor.end("注册成功");
            return ResponseEntity.ok(Map.of("code", 200, "message", "注册成功"));
        } catch (CustomException e) {
            LogUtils.logBusinessError("USER_REGISTER", "anonymous", "用户注册失败: %s", e, e.getMessage());
            monitor.end("注册失败: " + e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getCode(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("USER_REGISTER", "anonymous", "用户注册异常: %s", e, e.getMessage());
            monitor.end("注册异常: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", 500, "message", "服务器内部错误"));
        }
    }

    // 用户登录接口
    // 验证用户身份并生成JWT令牌
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("USER_LOGIN");
        try {
            if (request.email() == null || request.email().isEmpty() ||
                    request.password() == null || request.password().isEmpty()) {
                LogUtils.logUserOperation("anonymous", "LOGIN", "validation", "FAILED_EMPTY_PARAMS");
                return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "邮箱和密码不能为空"));
            }
            
            String username = userService.authenticateUser(request.email(), request.password());
            if (username == null) {
                LogUtils.logUserOperation("anonymous", "LOGIN", "authentication", "FAILED_INVALID_CREDENTIALS");
                return ResponseEntity.status(401).body(Map.of("code", 401, "message", "邮箱或密码错误"));
            }
            
            String token = jwtUtils.generateToken(username);
            String refreshToken = jwtUtils.generateRefreshToken(username);
            LogUtils.logUserOperation(username, "LOGIN", "token_generation", "SUCCESS");
            monitor.end("登录成功");
            
            return ResponseEntity.ok(Map.of("code", 200, "message", "登录成功", "data", Map.of(
                "token", token,
                "refreshToken", refreshToken
            )));
        } catch (CustomException e) {
            LogUtils.logBusinessError("USER_LOGIN", "anonymous", "登录失败: %s", e, e.getMessage());
            monitor.end("登录失败: " + e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getCode(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("USER_LOGIN", "anonymous", "登录异常: %s", e, e.getMessage());
            monitor.end("登录异常: " + e.getMessage());
            return ResponseEntity.status(500).body(Map.of("code", 500, "message", "服务器内部错误"));
        }
    }

    // 获取当前用户信息
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(@RequestHeader("Authorization") String token) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_USER_INFO");
        String username = null;
        try {
            username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
            if (username == null || username.isEmpty()) {
                LogUtils.logUserOperation("anonymous", "GET_USER_INFO", "token_validation", "FAILED_INVALID_TOKEN");
                monitor.end("获取用户信息失败：无效token");
                throw new CustomException("登录令牌无效", HttpStatus.UNAUTHORIZED);
            }

            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));

            // 手动构建返回对象，不包含 password 字段
            Map<String, Object> displayUserData = new LinkedHashMap<>();
            displayUserData.put("id", user.getId());
            displayUserData.put("username", user.getUsername());
            displayUserData.put("displayName", user.getDisplayName() == null ? user.getUsername() : user.getDisplayName());
            displayUserData.put("role", user.getRole());
            displayUserData.put("email", user.getEmail());
            displayUserData.put("emailVerified", user.getEmailVerifiedAt() != null);
            
            // 添加组织标签信息
            if (user.getOrgTags() != null && !user.getOrgTags().isEmpty()) {
                List<String> orgTagsList = Arrays.asList(user.getOrgTags().split(","));
                displayUserData.put("orgTags", orgTagsList);
            } else {
                displayUserData.put("orgTags", List.of());
            }
            
            // 添加主组织标签信息
            displayUserData.put("primaryOrg", user.getPrimaryOrg());
            
            displayUserData.put("createdAt", user.getCreatedAt());
            displayUserData.put("updatedAt", user.getUpdatedAt());

            LogUtils.logUserOperation(username, "GET_USER_INFO", "user_profile", "SUCCESS");
            monitor.end("获取用户信息成功");

            // 返回响应
            return ResponseEntity.ok(Map.of("code", 200, "message", "获取用户详情成功", "data", displayUserData));
        } catch (CustomException e) {
            LogUtils.logBusinessError("GET_USER_INFO", username, "获取用户信息失败: %s", e, e.getMessage());
            monitor.end("获取用户信息失败: " + e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_USER_INFO", username, "获取用户信息异常: %s", e, e.getMessage());
            monitor.end("获取用户信息异常: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", 500, "message", "服务器内部错误"));
        }
    }

    @PutMapping("/display-name")
    public ResponseEntity<?> updateDisplayName(@RequestHeader("Authorization") String token,
                                               @RequestBody DisplayNameRequest request) {
        String username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        String displayName = userService.updateDisplayName(username, request.displayName());
        return ResponseEntity.ok(Map.of("code", 200, "message", "昵称已更新", "data", Map.of("displayName", displayName)));
    }
    
    // 获取用户组织标签信息
    @GetMapping("/org-tags")
    public ResponseEntity<?> getUserOrgTags(@RequestHeader("Authorization") String token) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_USER_ORG_TAGS");
        String username = null;
        try {
            username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
            if (username == null || username.isEmpty()) {
                LogUtils.logUserOperation("anonymous", "GET_ORG_TAGS", "token_validation", "FAILED_INVALID_TOKEN");
                monitor.end("获取组织标签失败：无效token");
                throw new CustomException("登录令牌无效", HttpStatus.UNAUTHORIZED);
            }
            
            Map<String, Object> orgTagsInfo = userService.getUserOrgTags(username);
            
            LogUtils.logUserOperation(username, "GET_ORG_TAGS", "organization_tags", "SUCCESS");
            monitor.end("获取组织标签成功");
            
            return ResponseEntity.ok(Map.of(
                "code", 200, 
                "message", "获取用户组织标签成功",
                "data", orgTagsInfo
            ));
        } catch (CustomException e) {
            LogUtils.logBusinessError("GET_USER_ORG_TAGS", username, "获取用户组织标签失败: %s", e, e.getMessage());
            monitor.end("获取组织标签失败: " + e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_USER_ORG_TAGS", username, "获取用户组织标签异常: %s", e, e.getMessage());
            monitor.end("获取组织标签异常: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", 500, "message", "服务器内部错误"));
        }
    }
    
    // 设置用户主组织标签
    @PutMapping("/primary-org")
    public ResponseEntity<?> setPrimaryOrg(@RequestHeader("Authorization") String token, @RequestBody PrimaryOrgRequest request) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("SET_PRIMARY_ORG");
        String username = null;
        try {
            username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
            if (username == null || username.isEmpty()) {
                LogUtils.logUserOperation("anonymous", "SET_PRIMARY_ORG", "token_validation", "FAILED_INVALID_TOKEN");
                monitor.end("设置主组织失败：无效token");
                throw new CustomException("登录令牌无效", HttpStatus.UNAUTHORIZED);
            }
            
            if (request.primaryOrg() == null || request.primaryOrg().isEmpty()) {
                LogUtils.logUserOperation(username, "SET_PRIMARY_ORG", "validation", "FAILED_EMPTY_ORG");
                monitor.end("设置主组织失败：组织标签为空");
                return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "主组织标签不能为空"));
            }
            
            userService.setUserPrimaryOrg(username, request.primaryOrg());
            
            LogUtils.logUserOperation(username, "SET_PRIMARY_ORG", request.primaryOrg(), "SUCCESS");
            monitor.end("设置主组织成功");
            
            return ResponseEntity.ok(Map.of("code", 200, "message", "主组织设置成功"));
        } catch (CustomException e) {
            LogUtils.logBusinessError("SET_PRIMARY_ORG", username, "设置主组织失败: %s", e, e.getMessage());
            monitor.end("设置主组织失败: " + e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("SET_PRIMARY_ORG", username, "设置主组织异常: %s", e, e.getMessage());
            monitor.end("设置主组织异常: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", 500, "message", "服务器内部错误"));
        }
    }

    // 获取当前用户组织标签信息 (供上传文件时使用)
    @GetMapping("/upload-orgs")
    public ResponseEntity<?> getUploadOrgTags(@RequestAttribute("userId") String userId) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_UPLOAD_ORG_TAGS");
        try {
            LogUtils.logBusiness("GET_UPLOAD_ORG_TAGS", userId, "获取用户上传组织标签信息");
            
            // 获取用户所有组织标签
            List<String> orgTags = Arrays.asList(userService.getUserOrgTags(userId).get("orgTags").toString().split(","));
            // 获取用户主组织标签
            String primaryOrg = userService.getUserPrimaryOrg(userId);
            
            Map<String, Object> responseData = new HashMap<>();
            responseData.put("orgTags", orgTags);
            responseData.put("primaryOrg", primaryOrg);
            
            LogUtils.logUserOperation(userId, "GET_UPLOAD_ORG_TAGS", "upload_organizations", "SUCCESS");
            monitor.end("获取上传组织标签成功");
            
            return ResponseEntity.ok(Map.of(
                "code", 200, 
                "message", "获取用户上传组织标签成功", 
                "data", responseData
            ));
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_UPLOAD_ORG_TAGS", userId, "获取用户上传组织标签失败: %s", e, e.getMessage());
            monitor.end("获取上传组织标签失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "code", 500, 
                "message", "获取用户上传组织标签失败: " + e.getMessage()
            ));
        }
    }

    // 用户登出接口
    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader("Authorization") String token) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("USER_LOGOUT");
        String username = null;
        try {
            if (token == null || !token.startsWith("Bearer ")) {
                LogUtils.logUserOperation("anonymous", "LOGOUT", "validation", "FAILED_INVALID_TOKEN");
                monitor.end("登出失败：token格式无效");
                return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "登录令牌格式无效"));
            }

            String jwtToken = token.replace("Bearer ", "");
            username = jwtUtils.extractUsernameFromToken(jwtToken);
            
            if (username == null || username.isEmpty()) {
                LogUtils.logUserOperation("anonymous", "LOGOUT", "token_extraction", "FAILED_NO_USERNAME");
                monitor.end("登出失败：无法提取用户名");
                return ResponseEntity.status(401).body(Map.of("code", 401, "message", "登录令牌无效"));
            }

            // 使当前token失效
            jwtUtils.invalidateToken(jwtToken);
            
            LogUtils.logUserOperation(username, "LOGOUT", "token_invalidation", "SUCCESS");
            monitor.end("登出成功");

            return ResponseEntity.ok(Map.of("code", 200, "message", "退出登录成功"));
        } catch (Exception e) {
            LogUtils.logBusinessError("USER_LOGOUT", username, "登出异常: %s", e, e.getMessage());
            monitor.end("登出异常: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", 500, "message", "服务器内部错误"));
        }
    }

    // 用户批量登出接口（登出所有设备）
    @PostMapping("/logout-all")
    public ResponseEntity<?> logoutAll(@RequestHeader("Authorization") String token) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("USER_LOGOUT_ALL");
        String username = null;
        try {
            if (token == null || !token.startsWith("Bearer ")) {
                LogUtils.logUserOperation("anonymous", "LOGOUT_ALL", "validation", "FAILED_INVALID_TOKEN");
                monitor.end("批量登出失败：token格式无效");
                return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "登录令牌格式无效"));
            }

            String jwtToken = token.replace("Bearer ", "");
            username = jwtUtils.extractUsernameFromToken(jwtToken);
            String userId = jwtUtils.extractUserIdFromToken(jwtToken);
            
            if (username == null || username.isEmpty() || userId == null) {
                LogUtils.logUserOperation("anonymous", "LOGOUT_ALL", "token_extraction", "FAILED_NO_USER_INFO");
                monitor.end("批量登出失败：无法提取用户信息");
                return ResponseEntity.status(401).body(Map.of("code", 401, "message", "登录令牌无效"));
            }

            // 使用户所有token失效
            jwtUtils.invalidateAllUserTokens(userId);
            
            LogUtils.logUserOperation(username, "LOGOUT_ALL", "all_tokens_invalidation", "SUCCESS");
            monitor.end("批量登出成功");

            return ResponseEntity.ok(Map.of("code", 200, "message", "已退出所有设备"));
        } catch (Exception e) {
            LogUtils.logBusinessError("USER_LOGOUT_ALL", username, "批量登出异常: %s", e, e.getMessage());
            monitor.end("批量登出异常: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", 500, "message", "服务器内部错误"));
        }
    }
}

record RegistrationEmailRequest(String email) {}
record RegisterRequest(String email, String verificationCode, String password) {}
record ResetPasswordRequest(String email, String verificationCode, String password) {}
record LoginRequest(String email, String password) {}
record DisplayNameRequest(String displayName) {}

// 主组织标签请求记录类
record PrimaryOrgRequest(String primaryOrg) {}
