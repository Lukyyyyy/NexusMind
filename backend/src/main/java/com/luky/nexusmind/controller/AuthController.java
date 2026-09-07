package com.luky.nexusmind.controller;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.utils.JwtUtils;
import com.luky.nexusmind.utils.LogUtils;
import com.luky.nexusmind.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserRepository userRepository;

    /**
     * 刷新Token接口
     * 用于前端主动刷新token机制的后备方案
     */
    @PostMapping("/refreshToken")
    public ResponseEntity<?> refreshToken(@RequestBody RefreshTokenRequest request) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("REFRESH_TOKEN");
        String username = null;
        try {
            if (request.refreshToken() == null || request.refreshToken().isEmpty()) {
                LogUtils.logUserOperation("anonymous", "REFRESH_TOKEN", "validation", "FAILED_EMPTY_REFRESH_TOKEN");
                monitor.end("刷新token失败：refreshToken为空");
                return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "刷新令牌不能为空"));
            }

            username = jwtUtils.extractUsernameFromToken(request.refreshToken());
            if (username != null && userRepository.findByUsername(username).filter(user -> !user.isEnabled()).isPresent()) {
                throw new CustomException(JwtUtils.ACCOUNT_DISABLED_MESSAGE, HttpStatus.FORBIDDEN,
                        JwtUtils.ACCOUNT_DISABLED_CODE);
            }

            // 验证refreshToken是否有效（这里我们用相同的验证逻辑）
            if (!jwtUtils.validateRefreshToken(request.refreshToken())) {
                LogUtils.logUserOperation("anonymous", "REFRESH_TOKEN", "validation", "FAILED_INVALID_REFRESH_TOKEN");
                monitor.end("刷新token失败：refreshToken无效");
                return ResponseEntity.status(401).body(Map.of("code", 401, "message", "刷新令牌无效"));
            }

            // 从refreshToken中提取用户名
            username = jwtUtils.extractUsernameFromToken(request.refreshToken());
            if (username == null || username.isEmpty()) {
                LogUtils.logUserOperation("anonymous", "REFRESH_TOKEN", "extraction", "FAILED_NO_USERNAME");
                monitor.end("刷新token失败：无法提取用户名");
                return ResponseEntity.status(401).body(Map.of("code", 401, "message", "无法从刷新令牌中获取用户名"));
            }

            // 生成新的token和refreshToken
            String newToken = jwtUtils.generateToken(username);
            String newRefreshToken = jwtUtils.generateRefreshToken(username);

            LogUtils.logUserOperation(username, "REFRESH_TOKEN", "token_generation", "SUCCESS");
            monitor.end("刷新token成功");

            return ResponseEntity.ok(Map.of(
                "code", 200, 
                "message", "令牌刷新成功",
                "data", Map.of(
                    "token", newToken,
                    "refreshToken", newRefreshToken
                )
            ));
        } catch (CustomException e) {
            LogUtils.logBusinessError("REFRESH_TOKEN", username, "刷新token失败: %s", e, e.getMessage());
            monitor.end("刷新token失败: " + e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getCode(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("REFRESH_TOKEN", username, "刷新token异常: %s", e, e.getMessage());
            monitor.end("刷新token异常: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", 500, "message", "服务器内部错误"));
        }
    }

    /**
     * 自定义后端错误接口（用于测试）
     */
    @GetMapping("/error")
    public ResponseEntity<?> customBackendError(@RequestParam String code, @RequestParam String msg) {
        return ResponseEntity.status(Integer.parseInt(code)).body(Map.of("code", Integer.parseInt(code), "message", msg));
    }
}

// 刷新Token请求记录类
record RefreshTokenRequest(String refreshToken) {}
