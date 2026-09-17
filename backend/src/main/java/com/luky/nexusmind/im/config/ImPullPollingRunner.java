package com.luky.nexusmind.im.config;

import com.luky.nexusmind.im.channel.ImChannelAdapter;
import com.luky.nexusmind.im.channel.ImChannelConfigService;
import com.luky.nexusmind.im.channel.ImChannelRegistry;
import com.luky.nexusmind.im.channel.ImChannelRuntime;
import com.luky.nexusmind.im.channel.clawbot.ClawbotWeChatAdapter;
import com.luky.nexusmind.im.dispatch.ImInboundProducer;
import com.luky.nexusmind.im.model.ImChannelType;
import com.luky.nexusmind.im.model.ImInboundMessage;
import com.luky.nexusmind.model.ImChannelConfig;
import com.luky.nexusmind.repository.ImChannelConfigRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 拉取型渠道（iLink 长轮询）后台轮询器 - 多用户并发模式。
 *
 * 每个用户独立线程长轮询自己的机器人：启动时扫描所有已启用渠道配置，为每个用户启动一个轮询线程。
 * 定时扫描新增用户配置，自动启动新线程。
 */
@Component
public class ImPullPollingRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(ImPullPollingRunner.class);
    private static final long NORMAL_DELAY_MS = 500;
    private static final long ERROR_BACKOFF_BASE_MS = 5000;
    private static final long ERROR_BACKOFF_MAX_MS = 60000;
    private static final long SCAN_INTERVAL_MS = 30000;

    private final ImChannelRegistry registry;
    private final ImChannelConfigService configService;
    private final ImChannelConfigRepository configRepository;
    private final ImInboundProducer producer;
    private final ClawbotWeChatAdapter clawbotAdapter;
    private final long pollIdleDelayMs;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    private final Map<Long, Boolean> runningPollers = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    public ImPullPollingRunner(ImChannelRegistry registry,
                               ImChannelConfigService configService,
                               ImChannelConfigRepository configRepository,
                               ImInboundProducer producer,
                               List<ImChannelAdapter> adapters,
                               @Value("${im.pull-polling.idle-delay-ms:500}") long pollIdleDelayMs) {
        this.registry = registry;
        this.configService = configService;
        this.configRepository = configRepository;
        this.producer = producer;
        this.clawbotAdapter = adapters.stream()
                .filter(ClawbotWeChatAdapter.class::isInstance)
                .map(ClawbotWeChatAdapter.class::cast)
                .findFirst()
                .orElse(null);
        this.pollIdleDelayMs = pollIdleDelayMs;
    }

    @Override
    public void run(ApplicationArguments args) {
        executor.submit(this::scanLoop);
    }

    @PreDestroy
    public void stop() {
        running = false;
        runningPollers.clear();
        executor.shutdownNow();
    }

    private void scanLoop() {
        while (running) {
            try {
                if (clawbotAdapter == null || !registry.supports(ImChannelType.CLAWBOT_WECHAT)) {
                    sleep(5000);
                    continue;
                }

                List<ImChannelConfig> configs = configRepository.findByEnabled(true);
                for (ImChannelConfig config : configs) {
                    if (config.getChannelType() == ImChannelType.CLAWBOT_WECHAT
                            && !runningPollers.containsKey(config.getUserId())) {
                        startPoller(config.getUserId());
                    }
                }

                sleep(SCAN_INTERVAL_MS);
            } catch (Exception e) {
                logger.error("扫描渠道配置失败: {}", e.getMessage(), e);
                sleep(5000);
            }
        }
    }

    private void startPoller(Long userId) {
        runningPollers.put(userId, true);
        executor.submit(() -> pollLoop(userId));
        logger.info("启动用户 {} 的 iLink 长轮询", userId);
    }

    /** 新配置创建后（扫码登录成功）立即启动轮询器，无需等待 30s 扫描周期。 */
    public void ensurePoller(Long userId) {
        if (userId != null && !runningPollers.containsKey(userId)) {
            startPoller(userId);
        }
    }

    private void pollLoop(Long userId) {
        // 恢复该用户上次持久化的同步游标，避免重启后重复消费
        try {
            var existing = configRepository.findByChannelTypeAndUserId(
                    com.luky.nexusmind.im.model.ImChannelType.CLAWBOT_WECHAT, userId).orElse(null);
            if (existing != null) {
                var credentials = configService.readCredentials(existing);
                Object cursor = credentials.get("syncCursor");
                if (cursor instanceof String cursorText && !cursorText.isBlank()) {
                    clawbotAdapter.restoreCursor(userId, cursorText);
                }
            }
        } catch (Exception e) {
            logger.debug("恢复用户 {} 同步游标失败: {}", userId, e.getMessage());
        }
        // 注册游标持久化：写回该用户自己的配置行
        clawbotAdapter.setCursorListener((uid, cursor) -> {
            try {
                configService.patchCredentialField(
                        com.luky.nexusmind.im.model.ImChannelType.CLAWBOT_WECHAT, uid, "syncCursor", cursor);
            } catch (Exception e) {
                logger.warn("用户 {} 同步游标持久化失败: {}", uid, e.getMessage());
            }
        });
        long backoff = ERROR_BACKOFF_BASE_MS;
        boolean authExpired = false;
        while (running && runningPollers.getOrDefault(userId, false)) {
            try {
                ImChannelRuntime runtime;
                try {
                    runtime = configService.requireRuntime(ImChannelType.CLAWBOT_WECHAT, userId);
                } catch (IllegalArgumentException | IllegalStateException e) {
                    sleep(10000);
                    continue;
                }

                List<ImInboundMessage> messages = clawbotAdapter.poll(runtime);
                if (authExpired || "EXPIRED".equals(runtime.credentialText("tokenState"))) {
                    configService.patchCredentialField(ImChannelType.CLAWBOT_WECHAT, userId,
                            "tokenState", "ACTIVE");
                    authExpired = false;
                }
                backoff = ERROR_BACKOFF_BASE_MS;
                for (ImInboundMessage message : messages) {
                    clawbotAdapter.rememberContextToken(message, extractContextToken(message));
                    producer.publish(message);
                }
                sleep(messages.isEmpty() ? pollIdleDelayMs : NORMAL_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                String messageText = e.getMessage() == null ? "" : e.getMessage();
                if (messageText.contains("ret=-14") || messageText.contains("ret=-2")) {
                    if (!authExpired) {
                        configService.patchCredentialField(ImChannelType.CLAWBOT_WECHAT, userId,
                                "tokenState", "EXPIRED");
                        authExpired = true;
                    }
                    logger.error("用户 {} iLink 会话已过期，请重新扫码登录", userId);
                    sleep(60000);
                } else {
                    logger.warn("用户 {} iLink 长轮询异常，{}ms 后重试: {}", userId, backoff, messageText);
                    sleep(backoff);
                    backoff = Math.min(backoff * 3 / 2, ERROR_BACKOFF_MAX_MS);
                }
            }
        }
        runningPollers.remove(userId);
        logger.info("停止用户 {} 的 iLink 长轮询", userId);
    }

    private String extractContextToken(ImInboundMessage message) {
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(message.rawPayload());
            return node.path("context_token").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
