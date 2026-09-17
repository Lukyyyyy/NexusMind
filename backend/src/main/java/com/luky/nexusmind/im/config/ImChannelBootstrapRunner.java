package com.luky.nexusmind.im.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luky.nexusmind.im.channel.ImCryptoService;
import com.luky.nexusmind.im.channel.clawbot.ClawbotWeChatAdapter;
import com.luky.nexusmind.im.model.ImChannelType;
import com.luky.nexusmind.model.ImChannelConfig;
import com.luky.nexusmind.repository.ImChannelConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 启动时按 application.yml 的 im.clawbot.* 配置初始化 ClawBot 渠道（超级管理员 userId=1 的行）。
 *
 * 每个用户独立拥有自己的机器人：本启动器仅初始化超级管理员那一行（方便容器用
 * IM_CLAWBOT_BOT_TOKEN 预置）。其他用户首次扫码登录时由
 * ImClawbotLoginController 自动创建各自的渠道配置行。
 */
@Component
public class ImChannelBootstrapRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(ImChannelBootstrapRunner.class);
    private static final Long SUPER_ADMIN_USER_ID = 1L;

    private final ImChannelConfigRepository configRepository;
    private final ImCryptoService cryptoService;
    private final ClawbotWeChatAdapter clawbotAdapter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${im.clawbot.base-url:}")
    private String baseUrl;

    @Value("${im.clawbot.bot-token:}")
    private String botToken;

    @Value("${im.clawbot.bot-name:}")
    private String botName;

    @Value("${im.clawbot.group-require-mention:false}")
    private boolean groupRequireMention;

    @Value("${im.clawbot.max-outbound-chars:1600}")
    private int maxOutboundChars;

    @Value("${im.clawbot.placeholder-enabled:false}")
    private boolean placeholderEnabled;

    public ImChannelBootstrapRunner(ImChannelConfigRepository configRepository,
                                    ImCryptoService cryptoService,
                                    ClawbotWeChatAdapter clawbotAdapter) {
        this.configRepository = configRepository;
        this.cryptoService = cryptoService;
        this.clawbotAdapter = clawbotAdapter;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ImChannelConfig config = configRepository.findByChannelTypeAndUserId(
                    ImChannelType.CLAWBOT_WECHAT, SUPER_ADMIN_USER_ID).orElse(null);
            boolean envTokenPresent = botToken != null && !botToken.isBlank();

            if (config == null && !envTokenPresent) {
                logger.info("微信 ClawBot(iLink) 渠道未配置：可设置 IM_CLAWBOT_BOT_TOKEN 为超级管理员初始化，" +
                        "或其他用户通过 POST /api/v1/im/clawbot/login/start 扫码接入");
                return;
            }
            if (config == null) {
                config = new ImChannelConfig();
                config.setChannelType(ImChannelType.CLAWBOT_WECHAT);
                config.setUserId(SUPER_ADMIN_USER_ID);
                config.setName("微信 ClawBot");
                config.setEnabled(true);
            }
            if (baseUrl != null && !baseUrl.isBlank()) config.setBaseUrl(baseUrl);

            Map<String, Object> credentials = readCredentials(config);
            if (envTokenPresent) {
                credentials.put("botToken", botToken);
                if (botName != null && !botName.isBlank()) credentials.put("botName", botName);
            }
            config.setCredentialsCipher(cryptoService.encrypt(objectMapper.writeValueAsString(credentials)));
            config.setEnabled(true);
            config.setGroupRequireMention(groupRequireMention);
            config.setMaxOutboundChars(maxOutboundChars);
            config.setPlaceholderEnabled(placeholderEnabled);
            configRepository.save(config);

            Object cursor = credentials.get("syncCursor");
            if (cursor instanceof String cursorText) clawbotAdapter.restoreCursor(SUPER_ADMIN_USER_ID, cursorText);

            boolean hasToken = credentials.get("botToken") instanceof String token && !token.isBlank();
            logger.info("微信 ClawBot(iLink) 渠道已就绪（超级管理员），token: {}", hasToken ? "已配置" : "待扫码登录");
        } catch (Exception e) {
            logger.error("初始化微信 ClawBot 渠道失败: {}", e.getMessage(), e);
        }
    }

    /** 解密已有凭据；无凭据返回空 Map。异常时丢弃旧凭据（密钥轮换场景）。 */
    private Map<String, Object> readCredentials(ImChannelConfig config) {
        if (config.getCredentialsCipher() == null || config.getCredentialsCipher().isBlank()) {
            return new HashMap<>();
        }
        try {
            String plain = cryptoService.decrypt(config.getCredentialsCipher());
            if (plain == null || plain.isBlank()) return new HashMap<>();
            return objectMapper.readValue(plain,
                    objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, Object.class));
        } catch (Exception e) {
            logger.warn("旧渠道凭据解密失败，将重建凭据: {}", e.getMessage());
            return new HashMap<>();
        }
    }
}
