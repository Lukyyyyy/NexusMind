package com.luky.nexusmind.im.gateway;

import com.luky.nexusmind.im.channel.ImChannelConfigService;
import com.luky.nexusmind.im.channel.ImChannelRuntime;
import com.luky.nexusmind.im.channel.clawbot.ImIlinkLoginService;
import com.luky.nexusmind.im.config.ImPullPollingRunner;
import com.luky.nexusmind.im.model.ImChannelType;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.ImChannelConfigRepository;
import com.luky.nexusmind.repository.UserRepository;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 微信 ClawBot(iLink) 渠道端点：每用户独立扫码登录 + 状态查询，供前端「IM 渠道接入」页面调用。
 *
 * 流程：
 * 1. POST /api/v1/im/clawbot/login/start  -> 返回 qrcode（轮询凭据）与 qrcodeContent
 *    （liteapp 页面 URL，前端在本地 canvas 渲染二维码；该 URL 是 HTML 页面而非图片，
 *    后端无法代理成图片）
 * 2. 用户用自己的微信扫码并在手机上确认
 * 3. POST /api/v1/im/clawbot/login/poll {qrcode} -> 轮询状态，confirmed 时自动写入
 *    当前用户的渠道配置行（含 botId 实体列），并立即启动该用户的长轮询
 * 4. GET  /api/v1/im/clawbot/status -> 当前用户渠道状态
 *
 * 权限由 SecurityConfig 统一配置：所有登录用户可访问（每人连接自己的机器人）。
 */
@RestController
@RequestMapping("/api/v1/im/clawbot")
public class ImClawbotLoginController {

    private static final Logger logger = LoggerFactory.getLogger(ImClawbotLoginController.class);

    public record PollRequest(String qrcode) {
    }

    private final ImIlinkLoginService loginService;
    private final ImChannelConfigService configService;
    private final ImChannelConfigRepository configRepository;
    private final UserRepository userRepository;
    private final ImPullPollingRunner pollingRunner;
    private final String baseUrl;

    public ImClawbotLoginController(ImIlinkLoginService loginService,
                                    ImChannelConfigService configService,
                                    ImChannelConfigRepository configRepository,
                                    UserRepository userRepository,
                                    ImPullPollingRunner pollingRunner,
                                    @Value("${im.clawbot.base-url:https://ilinkai.weixin.qq.com}") String baseUrl) {
        this.loginService = loginService;
        this.configService = configService;
        this.configRepository = configRepository;
        this.userRepository = userRepository;
        this.pollingRunner = pollingRunner;
        this.baseUrl = baseUrl;
    }

    private Long currentUserId(Authentication auth) {
        User user = userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new IllegalStateException("用户不存在: " + auth.getName()));
        return user.getId();
    }

    @PostMapping("/login/start")
    public ResponseEntity<?> startLogin(Authentication auth) {
        Long userId = currentUserId(auth);
        try {
            ImIlinkLoginService.LoginStart start = loginService.startLogin(baseUrl);
            logger.info("iLink 二维码已生成，用户：{}", userId);
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "二维码已生成，请用微信扫码并在手机上确认",
                    "data", Map.of(
                            "qrcode", start.qrcode(),
                            "qrcodeContent", start.qrcodeImageUrl(),
                            "expireSeconds", start.expireSeconds())));
        } catch (Exception e) {
            logger.error("获取 iLink 登录二维码失败 (user {}): {}", userId, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("code", 500, "message", "获取 iLink 登录二维码失败: " + e.getMessage()));
        }
    }

    @PostMapping("/login/poll")
    public ResponseEntity<?> poll(@RequestBody PollRequest request, Authentication auth) {
        Long userId = currentUserId(auth);
        if (request == null || request.qrcode() == null || request.qrcode().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "qrcode 不能为空"));
        }
        try {
            ImIlinkLoginService.LoginPoll poll = loginService.pollStatus(baseUrl, request.qrcode());
            if (!"confirmed".equals(poll.status())) {
                return ResponseEntity.ok(Map.of("code", 200, "data",
                        Map.of("status", poll.status())));
            }
            ObjectNode patch = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            patch.put("botToken", poll.botToken());
            patch.put("tokenState", "ACTIVE");
            if (poll.botId() != null && !poll.botId().isBlank()) patch.put("botId", poll.botId());
            configService.patchCredentialsFromNode(ImChannelType.CLAWBOT_WECHAT, userId, patch);

            // botId 同时写实体列（findByChannelTypeAndBotId 依赖该列做归属路由）
            var config = configRepository.findByChannelTypeAndUserId(ImChannelType.CLAWBOT_WECHAT, userId).orElse(null);
            if (config != null) {
                if (poll.botId() != null && !poll.botId().isBlank()) config.setBotId(poll.botId());
                if (poll.baseUrl() != null && !poll.baseUrl().isBlank()) config.setBaseUrl(poll.baseUrl());
                if (!config.isEnabled()) config.setEnabled(true);
                configRepository.save(config);
            }
            // 立即启动该用户的长轮询，无需等待扫描周期
            pollingRunner.ensurePoller(userId);
            return ResponseEntity.ok(Map.of("code", 200, "message", "登录成功，渠道已可用", "data",
                    Map.of("status", "confirmed", "botId", poll.botId() == null ? "" : poll.botId())));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("code", 500, "message", "iLink 登录状态查询失败: " + e.getMessage()));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<?> status(Authentication auth) {
        Long userId = currentUserId(auth);
        boolean configured = configService.isConfigured(ImChannelType.CLAWBOT_WECHAT, userId);
        String tokenState = "NOT_CONFIGURED";
        String botId = "";
        if (configured) {
            try {
                ImChannelRuntime runtime = configService.requireRuntime(ImChannelType.CLAWBOT_WECHAT, userId);
                String token = runtime.credentialText("botToken");
                String id = runtime.credentialText("botId");
                if (id != null) botId = id;
                if (token == null || token.isBlank()) {
                    tokenState = "WAITING_LOGIN";
                } else {
                    String savedState = runtime.credentialText("tokenState");
                    tokenState = "EXPIRED".equals(savedState) ? "EXPIRED" : "ACTIVE";
                }
            } catch (Exception e) {
                tokenState = "DISABLED";
            }
        }
        return ResponseEntity.ok(Map.of("code", 200, "data",
                Map.of("configured", configured, "tokenState", tokenState, "botId", botId)));
    }
}
