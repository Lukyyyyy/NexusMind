package com.luky.nexusmind.im.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luky.nexusmind.im.model.ImChannelType;
import com.luky.nexusmind.im.model.ImInboundMessage;
import com.luky.nexusmind.model.ImChannelConfig;
import com.luky.nexusmind.repository.ImChannelConfigRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 渠道配置读取与凭据解密。密文格式与 ModelConfigCryptoService 相同（AES-GCM, Base64(iv+ct)），
 * 密钥独立于模型配置密钥。
 */
@Component
public class ImChannelConfigService {

    private final ImChannelConfigRepository repository;
    private final ImCryptoService cryptoService;
    private final ObjectMapper objectMapper;

    public ImChannelConfigService(ImChannelConfigRepository repository,
                                  ImCryptoService cryptoService,
                                  ObjectMapper objectMapper) {
        this.repository = repository;
        this.cryptoService = cryptoService;
        this.objectMapper = objectMapper;
    }

    public ImChannelRuntime requireRuntime(ImChannelType type, Long userId) {
        ImChannelConfig config = repository.findByChannelTypeAndUserId(type, userId)
                .orElseThrow(() -> new IllegalArgumentException("IM 渠道未配置: " + type));
        if (!config.isEnabled()) {
            throw new IllegalStateException("IM 渠道已停用: " + type);
        }
        JsonNode credentials;
        try {
            String plain = cryptoService.decrypt(config.getCredentialsCipher());
            credentials = plain == null || plain.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(plain);
        } catch (Exception e) {
            throw new IllegalStateException("IM 渠道凭据解密失败: " + type, e);
        }
        return new ImChannelRuntime(type, config.getUserId(), config.getName(), config.getBaseUrl(), credentials,
                config.isGroupRequireMention(), config.getMaxOutboundChars(), config.isPlaceholderEnabled());
    }

    /** 渠道是否已配置（不管是否启用）。 */
    public boolean isConfigured(ImChannelType type, Long userId) {
        return repository.findByChannelTypeAndUserId(type, userId).isPresent();
    }

    /** 读取凭据为可变 Map（解密失败返回空 Map）。 */
    public Map<String, Object> readCredentials(ImChannelConfig config) {
        if (config.getCredentialsCipher() == null || config.getCredentialsCipher().isBlank()) {
            return new HashMap<>();
        }
        try {
            String plain = cryptoService.decrypt(config.getCredentialsCipher());
            if (plain == null || plain.isBlank()) return new HashMap<>();
            return objectMapper.readValue(plain, objectMapper.getTypeFactory()
                    .constructMapType(HashMap.class, String.class, Object.class));
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    /** 用 Map 重建凭据密文并保存。 */
    public void saveCredentials(ImChannelConfig config, Map<String, Object> credentials) {
        try {
            config.setCredentialsCipher(cryptoService.encrypt(objectMapper.writeValueAsString(credentials)));
            repository.save(config);
        } catch (Exception e) {
            throw new IllegalStateException("IM 渠道凭据保存失败", e);
        }
    }

    /** 更新凭据中的单个字段（解密-合并-加密），用于同步游标等运行时状态的持久化。 */
    public synchronized void patchCredentialField(ImChannelType type, Long userId, String field, Object value) {
        Optional<ImChannelConfig> opt = repository.findByChannelTypeAndUserId(type, userId);
        if (opt.isEmpty()) return;
        ImChannelConfig config = opt.get();
        Map<String, Object> credentials = readCredentials(config);
        credentials.put(field, value);
        saveCredentials(config, credentials);
    }

    /** 合并式更新凭据字段（ObjectNode 视图，供登录流程写 token）。 */
    public synchronized void patchCredentialsFromNode(ImChannelType type, Long userId, ObjectNode patch) {
        ImChannelConfig config = repository.findByChannelTypeAndUserId(type, userId).orElseGet(() -> {
            ImChannelConfig created = new ImChannelConfig();
            created.setChannelType(type);
            created.setUserId(userId);
            created.setName(type.displayName());
            return created;
        });
        Map<String, Object> credentials = readCredentials(config);
        patch.fields().forEachRemaining(entry -> credentials.put(entry.getKey(),
                entry.getValue().isTextual() ? entry.getValue().asText() : entry.getValue().toString()));
        saveCredentials(config, credentials);
    }

    /** 列出指定渠道所有启用的配置（多用户回调验证 URL 时遍历）。 */
    public List<ImChannelRuntime> listEnabledRuntimes(ImChannelType type) {
        List<ImChannelRuntime> runtimes = new ArrayList<>();
        for (ImChannelConfig config : repository.findByEnabled(true)) {
            if (config.getChannelType() != type) continue;
            try {
                runtimes.add(requireRuntime(type, config.getUserId()));
            } catch (Exception e) {
                // 跳过解密失败的配置
            }
        }
        return runtimes;
    }

    /** 从消息提取 botId，查找对应用户的配置并返回 Runtime（多用户回调时使用）。 */
    public ImChannelRuntime findRuntimeByBotId(ImChannelType type, ImInboundMessage message) {
        try {
            JsonNode root = objectMapper.readTree(message.rawPayload());
            String botId = root.path("to_user_id").asText(root.path("to_user_name").asText(""));
            if (botId.isBlank()) return null;

            Optional<ImChannelConfig> config = repository.findByChannelTypeAndBotId(type, botId);
            if (config.isEmpty() || !config.get().isEnabled()) return null;

            return requireRuntime(type, config.get().getUserId());
        } catch (Exception e) {
            return null;
        }
    }
}
