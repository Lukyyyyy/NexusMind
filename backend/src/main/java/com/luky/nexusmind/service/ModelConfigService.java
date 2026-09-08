package com.luky.nexusmind.service;

import com.luky.nexusmind.config.AiProperties;
import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.model.AiModelConfig;
import com.luky.nexusmind.model.AiModelOwnerType;
import com.luky.nexusmind.model.AiModelType;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.model.UserModelPreference;
import com.luky.nexusmind.repository.AiModelConfigRepository;
import com.luky.nexusmind.repository.UserModelPreferenceRepository;
import com.luky.nexusmind.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class ModelConfigService {
    public static final int REQUIRED_EMBEDDING_DIMENSION = 2048;

    /** 各检索场景统一的最终返回条数（聊天/Agent/搜索的 topK），同时作为重排窗口的下限 */
    public static final int STANDARD_FINAL_TOP_K = 10;

    /** DashScope rerank 单次最大文本候选数 */
    private static final int RERANK_MAX_DOCS = 100;

    /** DashScope 原生 rerank 接口路径（qwen3-rerank / qwen3-vl-rerank 系列，非 OpenAI 兼容格式） */
    public static final String RERANK_ENDPOINT_PATH = "/api/v1/services/rerank/text-rerank/text-rerank";

    /** 全局重排候选窗口（ai.retrieval.rerank-top-n），作为模型配置 top_n 的默认值与上限 */
    private final int globalRerankWindow;

    private final AiModelConfigRepository configRepository;
    private final UserModelPreferenceRepository preferenceRepository;
    private final UserRepository userRepository;
    private final ModelConfigCryptoService cryptoService;
    private final AiProperties aiProperties;
    private final String legacyLlmBaseUrl;
    private final String legacyLlmApiKey;
    private final String legacyLlmModel;
    private final String legacyEmbeddingBaseUrl;
    private final String legacyEmbeddingApiKey;
    private final String legacyEmbeddingModel;
    private final int legacyEmbeddingBatchSize;
    private final boolean legacyEmbeddingConcurrentEnabled;
    private final int legacyEmbeddingMaxConcurrency;
    private final int legacyEmbeddingDimension;

    public ModelConfigService(
            AiModelConfigRepository configRepository,
            UserModelPreferenceRepository preferenceRepository,
            UserRepository userRepository,
            ModelConfigCryptoService cryptoService,
            AiProperties aiProperties,
            @Value("${deepseek.api.url}") String legacyLlmBaseUrl,
            @Value("${deepseek.api.key}") String legacyLlmApiKey,
            @Value("${deepseek.api.model}") String legacyLlmModel,
            @Value("${embedding.api.url}") String legacyEmbeddingBaseUrl,
            @Value("${embedding.api.key}") String legacyEmbeddingApiKey,
            @Value("${embedding.api.model}") String legacyEmbeddingModel,
            @Value("${embedding.api.batch-size:100}") int legacyEmbeddingBatchSize,
            @Value("${embedding.api.concurrent-enabled:false}") boolean legacyEmbeddingConcurrentEnabled,
            @Value("${embedding.api.max-concurrency:1}") int legacyEmbeddingMaxConcurrency,
            @Value("${embedding.api.dimension:2048}") int legacyEmbeddingDimension,
            @Value("${ai.retrieval.rerank-top-n:30}") int globalRerankWindow) {
        this.configRepository = configRepository;
        this.preferenceRepository = preferenceRepository;
        this.userRepository = userRepository;
        this.cryptoService = cryptoService;
        this.aiProperties = aiProperties;
        this.legacyLlmBaseUrl = legacyLlmBaseUrl;
        this.legacyLlmApiKey = legacyLlmApiKey;
        this.legacyLlmModel = legacyLlmModel;
        this.legacyEmbeddingBaseUrl = legacyEmbeddingBaseUrl;
        this.legacyEmbeddingApiKey = legacyEmbeddingApiKey;
        this.legacyEmbeddingModel = legacyEmbeddingModel;
        this.legacyEmbeddingBatchSize = legacyEmbeddingBatchSize;
        this.legacyEmbeddingConcurrentEnabled = legacyEmbeddingConcurrentEnabled;
        this.legacyEmbeddingMaxConcurrency = legacyEmbeddingMaxConcurrency;
        this.legacyEmbeddingDimension = legacyEmbeddingDimension;
        this.globalRerankWindow = globalRerankWindow;
    }

    @Transactional(readOnly = true)
    public ModelConfigOverview listVisibleConfigs(String username) {
        User user = requireUser(username);
        List<AiModelConfig> configs = new ArrayList<>();
        if (user.getRole().isAdministrator()) {
            configs.addAll(configRepository.findByOwnerType(AiModelOwnerType.SYSTEM));
        } else {
            configs.addAll(configRepository.findByOwnerTypeAndEnabledTrue(AiModelOwnerType.SYSTEM));
        }
        configs.addAll(configRepository.findByOwnerTypeAndOwnerUserId(AiModelOwnerType.USER, user.getId()));
        configs.sort(Comparator.comparing(AiModelConfig::getModelType).thenComparing(AiModelConfig::getName));
        UserModelPreference preference = preferenceRepository.findByUserId(user.getId()).orElse(null);
        return new ModelConfigOverview(
                configs.stream().map(this::toResponse).toList(),
                effectiveSelectedConfigId(user, preference, AiModelType.LLM),
                effectiveSelectedConfigId(user, preference, AiModelType.EMBEDDING),
                effectiveGraphPreferenceId(user, preference),
                effectiveRerankPreferenceId(user, preference),
                STANDARD_FINAL_TOP_K,
                Math.min(RERANK_MAX_DOCS, globalRerankWindow),
                user.getRole().isAdministrator());
    }

    @Transactional
    public ModelConfigResponse createConfig(String username, ModelConfigRequest request) {
        User user = requireUser(username);
        validateRequest(user, request);
        AiModelConfig config = new AiModelConfig();
        applyRequest(config, request, user, true);
        AiModelConfig saved = configRepository.save(config);
        if (saved.isDefaultModel()) {
            clearOtherDefaults(saved);
        }
        return toResponse(saved);
    }

    @Transactional
    public ModelConfigResponse updateConfig(String username, Long id, ModelConfigRequest request) {
        User user = requireUser(username);
        AiModelConfig config = requireEditableConfig(user, id);
        validateRequest(user, request);
        applyRequest(config, request, user, false);
        AiModelConfig saved = configRepository.save(config);
        if (saved.isDefaultModel()) {
            clearOtherDefaults(saved);
        }
        return toResponse(saved);
    }

    @Transactional
    public void deleteConfig(String username, Long id) {
        User user = requireUser(username);
        AiModelConfig config = requireEditableConfig(user, id);
        configRepository.delete(config);
    }

    @Transactional
    public PreferenceResponse updatePreference(String username, PreferenceRequest request) {
        User user = requireUser(username);
        if (request == null || request.llmConfigId() == null || request.embeddingConfigId() == null) {
            throw new CustomException("请选择 LLM 和向量化模型后再保存", HttpStatus.BAD_REQUEST);
        }
        Long llmConfigId = validateSelectableConfig(user, request.llmConfigId(), AiModelType.LLM);
        Long embeddingConfigId = validateSelectableConfig(user, request.embeddingConfigId(), AiModelType.EMBEDDING);
        Long graphExtractionConfigId = request.graphExtractionConfigId() == null
                ? null
                : validateSelectableConfig(user, request.graphExtractionConfigId(), AiModelType.LLM);
        Long rerankConfigId = request.rerankConfigId() == null
                ? null
                : validateSelectableConfig(user, request.rerankConfigId(), AiModelType.RERANK);
        UserModelPreference preference = preferenceRepository.findByUserId(user.getId()).orElseGet(() -> {
            UserModelPreference created = new UserModelPreference();
            created.setUserId(user.getId());
            return created;
        });
        preference.setLlmConfigId(llmConfigId);
        preference.setEmbeddingConfigId(embeddingConfigId);
        preference.setGraphExtractionConfigId(graphExtractionConfigId);
        preference.setRerankConfigId(rerankConfigId);
        UserModelPreference saved = preferenceRepository.save(preference);
        return new PreferenceResponse(saved.getLlmConfigId(), saved.getEmbeddingConfigId(),
                saved.getGraphExtractionConfigId(), saved.getRerankConfigId());
    }

    @Transactional(readOnly = true)
    public ResolvedModelConfig resolveLlmConfig(String username) {
        return resolveConfig(username, AiModelType.LLM).orElseGet(this::legacyLlmConfig);
    }

    @Transactional(readOnly = true)
    public ResolvedModelConfig resolveLlmConfig(String username, Long configId) {
        if (configId == null) return resolveLlmConfig(username);
        User user = requireUser(username);
        return configRepository.findById(configId)
                .filter(config -> config.getModelType() == AiModelType.LLM)
                .filter(AiModelConfig::isEnabled)
                .filter(config -> canView(user, config))
                .map(this::toResolved)
                .orElseThrow(() -> new CustomException("模型不存在、已停用或无权使用", HttpStatus.BAD_REQUEST));
    }

    @Transactional
    public ResolvedModelConfig selectLlmConfig(String username, Long configId) {
        if (configId == null) throw new CustomException("请选择模型", HttpStatus.BAD_REQUEST);
        User user = requireUser(username);
        ResolvedModelConfig resolved = resolveLlmConfig(username, configId);
        UserModelPreference preference = preferenceRepository.findByUserId(user.getId()).orElseGet(() -> {
            UserModelPreference created = new UserModelPreference();
            created.setUserId(user.getId());
            return created;
        });
        preference.setLlmConfigId(resolved.id());
        preferenceRepository.save(preference);
        return resolved;
    }

    @Transactional(readOnly = true)
    public ResolvedModelConfig resolveEmbeddingConfig(String username) {
        return resolveConfig(username, AiModelType.EMBEDDING).orElseGet(this::legacyEmbeddingConfig);
    }

    /** Graph extraction follows the chat model unless an explicit LLM is selected. */
    @Transactional(readOnly = true)
    public ResolvedModelConfig resolveGraphExtractionConfig(String username) {
        User user = findUserFlexible(username);
        if (user == null) {
            return resolveLlmConfig(username);
        }
        UserModelPreference preference = preferenceRepository.findByUserId(user.getId()).orElse(null);
        if (preference != null && preference.getGraphExtractionConfigId() != null) {
            Optional<AiModelConfig> selected = configRepository.findById(preference.getGraphExtractionConfigId())
                    .filter(config -> config.getModelType() == AiModelType.LLM)
                    .filter(AiModelConfig::isEnabled)
                    .filter(config -> canView(user, config));
            if (selected.isPresent()) {
                return toResolved(selected.get());
            }
        }
        return resolveLlmConfig(username);
    }

    /**
     * Rerank 模型解析：显式偏好 > 用户自建且启用的 RERANK 配置 > 系统默认；均无时返回 empty（表示 rerank 关闭）。
     */
    @Transactional(readOnly = true)
    public Optional<ResolvedModelConfig> resolveRerankConfig(String userIdOrName) {
        User user = findUserFlexible(userIdOrName);
        if (user != null) {
            UserModelPreference preference = preferenceRepository.findByUserId(user.getId()).orElse(null);
            if (preference != null && preference.getRerankConfigId() != null) {
                Optional<AiModelConfig> selected = configRepository.findById(preference.getRerankConfigId())
                        .filter(config -> config.getModelType() == AiModelType.RERANK)
                        .filter(AiModelConfig::isEnabled)
                        .filter(config -> canView(user, config));
                if (selected.isPresent()) {
                    return selected.map(this::toResolved);
                }
            }
            Optional<AiModelConfig> own = configRepository
                    .findFirstByOwnerTypeAndModelTypeAndOwnerUserIdAndEnabledTrueOrderByIdAsc(
                            AiModelOwnerType.USER, AiModelType.RERANK, user.getId());
            if (own.isPresent()) {
                return own.map(this::toResolved);
            }
        }
        return resolveSystemDefault(AiModelType.RERANK);
    }

    private User findUserFlexible(String userIdOrName) {
        if (!hasText(userIdOrName)) {
            return null;
        }
        try {
            return userRepository.findById(Long.parseLong(userIdOrName)).orElse(null);
        } catch (NumberFormatException e) {
            return userRepository.findByUsername(userIdOrName).orElse(null);
        }
    }

    private Long effectiveGraphPreferenceId(User user, UserModelPreference preference) {
        if (preference == null || preference.getGraphExtractionConfigId() == null) {
            return null;
        }
        return configRepository.findById(preference.getGraphExtractionConfigId())
                .filter(config -> config.getModelType() == AiModelType.LLM)
                .filter(AiModelConfig::isEnabled)
                .filter(config -> canView(user, config))
                .map(AiModelConfig::getId)
                .orElse(null);
    }

    /** 展示用：显式 Rerank 偏好生效时返回其 ID，否则回落到系统默认 Rerank 配置 */
    private Long effectiveRerankPreferenceId(User user, UserModelPreference preference) {
        if (preference != null && preference.getRerankConfigId() != null) {
            Optional<AiModelConfig> selected = configRepository.findById(preference.getRerankConfigId())
                    .filter(config -> config.getModelType() == AiModelType.RERANK)
                    .filter(AiModelConfig::isEnabled)
                    .filter(config -> canView(user, config));
            if (selected.isPresent()) {
                return preference.getRerankConfigId();
            }
        }
        return resolveSystemDefault(AiModelType.RERANK)
                .map(ResolvedModelConfig::id)
                .orElse(null);
    }

    private Optional<ResolvedModelConfig> resolveConfig(String username, AiModelType modelType) {
        if (!hasText(username)) {
            return resolveSystemDefault(modelType);
        }
        // 兼容数字用户 ID 与用户名两种形式（链路追踪层传递的可能是任一种）
        User user = findUserFlexible(username);
        if (user == null) {
            return resolveSystemDefault(modelType);
        }
        UserModelPreference preference = preferenceRepository.findByUserId(user.getId()).orElse(null);
        Long preferredId = null;
        if (preference != null) {
            preferredId = modelType == AiModelType.LLM ? preference.getLlmConfigId() : preference.getEmbeddingConfigId();
        }
        if (preferredId != null) {
            Optional<AiModelConfig> preferred = configRepository.findById(preferredId)
                    .filter(config -> config.getModelType() == modelType)
                    .filter(AiModelConfig::isEnabled)
                    .filter(config -> canView(user, config));
            if (preferred.isPresent()) {
                return preferred.map(this::toResolved);
            }
        }
        return resolveSystemDefault(modelType);
    }

    private Long effectiveSelectedConfigId(User user, UserModelPreference preference, AiModelType modelType) {
        Long preferredId = null;
        if (preference != null) {
            preferredId = modelType == AiModelType.LLM ? preference.getLlmConfigId() : preference.getEmbeddingConfigId();
        }
        if (preferredId != null) {
            Optional<AiModelConfig> preferred = configRepository.findById(preferredId)
                    .filter(config -> config.getModelType() == modelType)
                    .filter(AiModelConfig::isEnabled)
                    .filter(config -> canView(user, config));
            if (preferred.isPresent()) {
                return preferredId;
            }
        }
        return resolveSystemDefault(modelType)
                .map(ResolvedModelConfig::id)
                .orElse(null);
    }

    private Optional<ResolvedModelConfig> resolveSystemDefault(AiModelType modelType) {
        return configRepository.findFirstByOwnerTypeAndModelTypeAndDefaultModelTrueAndEnabledTrue(
                        AiModelOwnerType.SYSTEM,
                        modelType)
                .map(this::toResolved);
    }

    private void validateRequest(User user, ModelConfigRequest request) {
        if (request.ownerType() == AiModelOwnerType.SYSTEM && !user.getRole().isAdministrator()) {
            throw new CustomException("仅管理员可管理系统模型配置", HttpStatus.FORBIDDEN);
        }
        if (!hasText(request.name()) || !hasText(request.baseUrl()) || !hasText(request.modelName())) {
            throw new CustomException("模型名称、基础 URL 和模型 ID 不能为空", HttpStatus.BAD_REQUEST);
        }
        if (request.modelType() == AiModelType.EMBEDDING) {
            int dimension = request.dimension() != null ? request.dimension() : REQUIRED_EMBEDDING_DIMENSION;
            if (dimension != REQUIRED_EMBEDDING_DIMENSION) {
                throw new CustomException("向量维度必须为 2048", HttpStatus.BAD_REQUEST);
            }
            if (request.batchSize() != null && (request.batchSize() < 1 || request.batchSize() > 10)) {
                throw new CustomException("向量化批量大小必须在 1 到 10 之间", HttpStatus.BAD_REQUEST);
            }
            if (request.maxConcurrency() != null && (request.maxConcurrency() < 1 || request.maxConcurrency() > 30)) {
                throw new CustomException("向量化最大并发数必须在 1 到 30 之间", HttpStatus.BAD_REQUEST);
            }
        }
        if (request.modelType() == AiModelType.LLM) {
            if (request.maxTokens() != null && (request.maxTokens() < 1))
                throw new CustomException("maxTokens 必须为正整数", HttpStatus.BAD_REQUEST);
            if (request.maxToolCalls() != null && (request.maxToolCalls() < 1 || request.maxToolCalls() > 20))
                throw new CustomException("最大工具调用次数必须在 1 到 20 之间", HttpStatus.BAD_REQUEST);
            if (request.maxConcurrency() != null && (request.maxConcurrency() < 1 || request.maxConcurrency() > 30))
                throw new CustomException("图谱并发数必须在 1 到 30 之间", HttpStatus.BAD_REQUEST);
        }
        if (request.modelType() == AiModelType.RERANK) {
            if (request.topN() != null) {
                if (request.topN() < STANDARD_FINAL_TOP_K) {
                    throw new CustomException(
                            "重排窗口 top_n 不能小于最终返回条数 topK（" + STANDARD_FINAL_TOP_K + "）",
                            HttpStatus.BAD_REQUEST);
                }
                int windowCeiling = Math.min(RERANK_MAX_DOCS, globalRerankWindow);
                if (request.topN() > windowCeiling) {
                    throw new CustomException(
                            "重排窗口 top_n 不能超过全局融合窗口（" + windowCeiling + "）",
                            HttpStatus.BAD_REQUEST);
                }
            }
            if (request.fps() != null && (request.fps() < 0 || request.fps() > 1)) {
                throw new CustomException("Rerank fps 必须在 0 到 1 之间", HttpStatus.BAD_REQUEST);
            }
            if (request.instruct() != null && request.instruct().length() > 2000) {
                throw new CustomException("Rerank 指令长度不能超过 2000 字符", HttpStatus.BAD_REQUEST);
            }
        }
        if (Boolean.TRUE.equals(request.defaultModel()) && request.ownerType() != AiModelOwnerType.SYSTEM) {
            throw new CustomException("仅系统模型配置可设为默认", HttpStatus.BAD_REQUEST);
        }
    }

    private void applyRequest(AiModelConfig config, ModelConfigRequest request, User user, boolean creating) {
        config.setOwnerType(request.ownerType());
        config.setOwnerUserId(request.ownerType() == AiModelOwnerType.USER ? user.getId() : null);
        config.setModelType(request.modelType());
        config.setName(request.name().trim());
        config.setProvider(trimToNull(request.provider()));
        config.setBaseUrl(normalizeBaseUrl(request.baseUrl(), request.modelType()));
        if (creating || hasText(request.apiKey())) {
            config.setApiKeyEncrypted(cryptoService.encrypt(request.apiKey()));
        }
        config.setModelName(request.modelName().trim());
        config.setEnabled(request.enabled() == null || request.enabled());
        config.setDefaultModel(Boolean.TRUE.equals(request.defaultModel()));
        config.setTemperature(request.temperature());
        config.setTopP(request.topP());
        config.setMaxTokens(request.maxTokens());
        config.setMaxToolCalls(request.modelType() == AiModelType.LLM ? request.maxToolCalls() : null);
        config.setDimension(request.modelType() == AiModelType.EMBEDDING
                ? REQUIRED_EMBEDDING_DIMENSION
                : null);
        config.setBatchSize(request.batchSize());
        config.setMaxConcurrency(request.maxConcurrency());
        boolean rerank = request.modelType() == AiModelType.RERANK;
        config.setInstruct(rerank ? trimToNull(request.instruct()) : null);
        config.setTopN(rerank ? request.topN() : null);
        config.setFps(rerank ? request.fps() : null);
    }

    private AiModelConfig requireEditableConfig(User user, Long id) {
        AiModelConfig config = configRepository.findById(id)
                .orElseThrow(() -> new CustomException("模型配置不存在", HttpStatus.NOT_FOUND));
        if (config.getOwnerType() == AiModelOwnerType.SYSTEM && !user.getRole().isAdministrator()) {
            throw new CustomException("仅管理员可管理系统模型配置", HttpStatus.FORBIDDEN);
        }
        if (config.getOwnerType() == AiModelOwnerType.USER && !user.getId().equals(config.getOwnerUserId())) {
            throw new CustomException("不能修改其他用户的模型配置", HttpStatus.FORBIDDEN);
        }
        return config;
    }

    private Long validateSelectableConfig(User user, Long id, AiModelType expectedType) {
        if (id == null) {
            return null;
        }
        AiModelConfig config = configRepository.findById(id)
                .orElseThrow(() -> new CustomException("模型配置不存在", HttpStatus.NOT_FOUND));
        if (config.getModelType() != expectedType || !config.isEnabled() || !canView(user, config)) {
            throw new CustomException("该模型配置不可选择", HttpStatus.BAD_REQUEST);
        }
        return id;
    }

    private boolean canView(User user, AiModelConfig config) {
        if (config.getOwnerType() == AiModelOwnerType.SYSTEM) {
            return config.isEnabled() || user.getRole().isAdministrator();
        }
        return user.getId().equals(config.getOwnerUserId());
    }

    private void clearOtherDefaults(AiModelConfig selected) {
        List<AiModelConfig> configs = configRepository.findByOwnerTypeAndModelType(
                AiModelOwnerType.SYSTEM,
                selected.getModelType());
        for (AiModelConfig config : configs) {
            if (!config.getId().equals(selected.getId()) && config.isDefaultModel()) {
                config.setDefaultModel(false);
                configRepository.save(config);
            }
        }
    }

    private User requireUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
    }

    private ResolvedModelConfig toResolved(AiModelConfig config) {
        return new ResolvedModelConfig(
                config.getId(),
                config.getOwnerType(),
                config.getModelType(),
                config.getName(),
                normalizeBaseUrl(config.getBaseUrl(), config.getModelType()),
                cryptoService.decrypt(config.getApiKeyEncrypted()),
                config.getModelName(),
                config.getTemperature(),
                config.getTopP(),
                config.getMaxTokens(),
                config.getMaxToolCalls(),
                config.getDimension(),
                config.getBatchSize(),
                config.getMaxConcurrency(),
                config.getInstruct(),
                config.getTopN(),
                config.getFps());
    }

    private ResolvedModelConfig legacyLlmConfig() {
        AiProperties.Generation gen = aiProperties.getGeneration();
        return new ResolvedModelConfig(
                null,
                AiModelOwnerType.SYSTEM,
                AiModelType.LLM,
                "YAML 默认 LLM",
                normalizeBaseUrl(legacyLlmBaseUrl, AiModelType.LLM),
                legacyLlmApiKey,
                legacyLlmModel,
                gen.getTemperature(),
                gen.getTopP(),
                gen.getMaxTokens(),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private ResolvedModelConfig legacyEmbeddingConfig() {
        return new ResolvedModelConfig(
                null,
                AiModelOwnerType.SYSTEM,
                AiModelType.EMBEDDING,
                "YAML 默认向量模型",
                normalizeBaseUrl(legacyEmbeddingBaseUrl, AiModelType.EMBEDDING),
                legacyEmbeddingApiKey,
                legacyEmbeddingModel,
                null,
                null,
                null,
                null,
                legacyEmbeddingDimension,
                legacyEmbeddingBatchSize,
                legacyEmbeddingConcurrentEnabled ? legacyEmbeddingMaxConcurrency : 1,
                null,
                null,
                null);
    }

    private ModelConfigResponse toResponse(AiModelConfig config) {
        return new ModelConfigResponse(
                config.getId(),
                config.getOwnerType(),
                config.getOwnerUserId(),
                config.getModelType(),
                config.getName(),
                config.getProvider(),
                normalizeBaseUrl(config.getBaseUrl(), config.getModelType()),
                maskApiKey(config.getApiKeyEncrypted()),
                config.getModelName(),
                config.isEnabled(),
                config.isDefaultModel(),
                config.getTemperature(),
                config.getTopP(),
                config.getMaxTokens(),
                config.getMaxToolCalls(),
                config.getDimension(),
                config.getBatchSize(),
                config.getMaxConcurrency(),
                config.getInstruct(),
                config.getTopN(),
                config.getFps());
    }

    private String maskApiKey(String encrypted) {
        String value = cryptoService.decrypt(encrypted);
        if (!hasText(value)) {
            return "";
        }
        if (value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    static String normalizeBaseUrl(String value, AiModelType modelType) {
        String normalized = trimTrailingSlash(value);
        if (normalized == null) {
            return null;
        }
        if (modelType == AiModelType.RERANK) {
            return normalized.endsWith(RERANK_ENDPOINT_PATH)
                    ? trimTrailingSlash(normalized.substring(0, normalized.length() - RERANK_ENDPOINT_PATH.length()))
                    : normalized;
        }
        String endpoint = modelType == AiModelType.EMBEDDING ? "/embeddings" : "/chat/completions";
        return normalized != null && normalized.endsWith(endpoint)
                ? trimTrailingSlash(normalized.substring(0, normalized.length() - endpoint.length()))
                : normalized;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public record ModelConfigRequest(
            AiModelOwnerType ownerType,
            AiModelType modelType,
            String name,
            String provider,
            String baseUrl,
            String apiKey,
            String modelName,
            Boolean enabled,
            Boolean defaultModel,
            Double temperature,
            Double topP,
            Integer maxTokens,
            Integer maxToolCalls,
            Integer dimension,
            Integer batchSize,
            Integer maxConcurrency,
            String instruct,
            Integer topN,
            Double fps) {
    }

    public record ModelConfigResponse(
            Long id,
            AiModelOwnerType ownerType,
            Long ownerUserId,
            AiModelType modelType,
            String name,
            String provider,
            String baseUrl,
            String apiKey,
            String modelName,
            boolean enabled,
            boolean defaultModel,
            Double temperature,
            Double topP,
            Integer maxTokens,
            Integer maxToolCalls,
            Integer dimension,
            Integer batchSize,
            Integer maxConcurrency,
            String instruct,
            Integer topN,
            Double fps) {
    }

    public record ModelConfigOverview(
            List<ModelConfigResponse> configs,
            Long selectedLlmConfigId,
            Long selectedEmbeddingConfigId,
            Long selectedGraphExtractionConfigId,
            Long selectedRerankConfigId,
            int rerankWindowMin,
            int rerankWindowMax,
            boolean admin) {
    }

    public record PreferenceRequest(Long llmConfigId, Long embeddingConfigId, Long graphExtractionConfigId,
                                    Long rerankConfigId) {
    }

    public record PreferenceResponse(Long llmConfigId, Long embeddingConfigId, Long graphExtractionConfigId,
                                     Long rerankConfigId) {
    }

    public record ResolvedModelConfig(
            Long id,
            AiModelOwnerType ownerType,
            AiModelType modelType,
            String name,
            String baseUrl,
            String apiKey,
            String modelName,
            Double temperature,
            Double topP,
            Integer maxTokens,
            Integer maxToolCalls,
            Integer dimension,
            Integer batchSize,
            Integer maxConcurrency,
            String instruct,
            Integer topN,
            Double fps) {
    }
}
