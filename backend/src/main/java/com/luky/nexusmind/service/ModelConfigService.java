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
            @Value("${embedding.api.dimension:2048}") int legacyEmbeddingDimension) {
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
    }

    @Transactional(readOnly = true)
    public ModelConfigOverview listVisibleConfigs(String username) {
        User user = requireUser(username);
        List<AiModelConfig> configs = new ArrayList<>();
        if (user.getRole() == User.Role.ADMIN) {
            configs.addAll(configRepository.findByOwnerType(AiModelOwnerType.SYSTEM));
        } else {
            configs.addAll(configRepository.findByOwnerTypeAndEnabledTrue(AiModelOwnerType.SYSTEM));
        }
        configs.addAll(configRepository.findByOwnerTypeAndOwnerUserId(AiModelOwnerType.USER, user.getId()));
        configs.sort(Comparator.comparing(AiModelConfig::getModelType).thenComparing(AiModelConfig::getName));
        UserModelPreference preference = preferenceRepository.findByUserId(user.getId()).orElse(null);
        return new ModelConfigOverview(
                configs.stream().map(this::toResponse).toList(),
                preference != null ? preference.getLlmConfigId() : null,
                preference != null ? preference.getEmbeddingConfigId() : null,
                user.getRole() == User.Role.ADMIN);
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
        UserModelPreference preference = preferenceRepository.findByUserId(user.getId()).orElseGet(() -> {
            UserModelPreference created = new UserModelPreference();
            created.setUserId(user.getId());
            return created;
        });
        preference.setLlmConfigId(llmConfigId);
        preference.setEmbeddingConfigId(embeddingConfigId);
        UserModelPreference saved = preferenceRepository.save(preference);
        return new PreferenceResponse(saved.getLlmConfigId(), saved.getEmbeddingConfigId());
    }

    @Transactional(readOnly = true)
    public ResolvedModelConfig resolveLlmConfig(String username) {
        return resolveConfig(username, AiModelType.LLM).orElseGet(this::legacyLlmConfig);
    }

    @Transactional(readOnly = true)
    public ResolvedModelConfig resolveEmbeddingConfig(String username) {
        return resolveConfig(username, AiModelType.EMBEDDING).orElseGet(this::legacyEmbeddingConfig);
    }

    private Optional<ResolvedModelConfig> resolveConfig(String username, AiModelType modelType) {
        if (!hasText(username)) {
            return resolveSystemDefault(modelType);
        }
        User user = userRepository.findByUsername(username).orElse(null);
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

    private Optional<ResolvedModelConfig> resolveSystemDefault(AiModelType modelType) {
        return configRepository.findFirstByOwnerTypeAndModelTypeAndDefaultModelTrueAndEnabledTrue(
                        AiModelOwnerType.SYSTEM,
                        modelType)
                .map(this::toResolved);
    }

    private void validateRequest(User user, ModelConfigRequest request) {
        if (request.ownerType() == AiModelOwnerType.SYSTEM && user.getRole() != User.Role.ADMIN) {
            throw new CustomException("Only administrators can manage system model configs", HttpStatus.FORBIDDEN);
        }
        if (!hasText(request.name()) || !hasText(request.baseUrl()) || !hasText(request.modelName())) {
            throw new CustomException("Model name, base URL and model ID are required", HttpStatus.BAD_REQUEST);
        }
        if (request.modelType() == AiModelType.EMBEDDING) {
            int dimension = request.dimension() != null ? request.dimension() : REQUIRED_EMBEDDING_DIMENSION;
            if (dimension != REQUIRED_EMBEDDING_DIMENSION) {
                throw new CustomException("Embedding dimension must be 2048", HttpStatus.BAD_REQUEST);
            }
        }
        if (Boolean.TRUE.equals(request.defaultModel()) && request.ownerType() != AiModelOwnerType.SYSTEM) {
            throw new CustomException("Only system model configs can be default", HttpStatus.BAD_REQUEST);
        }
    }

    private void applyRequest(AiModelConfig config, ModelConfigRequest request, User user, boolean creating) {
        config.setOwnerType(request.ownerType());
        config.setOwnerUserId(request.ownerType() == AiModelOwnerType.USER ? user.getId() : null);
        config.setModelType(request.modelType());
        config.setName(request.name().trim());
        config.setProvider(trimToNull(request.provider()));
        config.setBaseUrl(trimTrailingSlash(request.baseUrl()));
        if (creating || hasText(request.apiKey())) {
            config.setApiKeyEncrypted(cryptoService.encrypt(request.apiKey()));
        }
        config.setModelName(request.modelName().trim());
        config.setEnabled(request.enabled() == null || request.enabled());
        config.setDefaultModel(Boolean.TRUE.equals(request.defaultModel()));
        config.setTemperature(request.temperature());
        config.setTopP(request.topP());
        config.setMaxTokens(request.maxTokens());
        config.setDimension(request.modelType() == AiModelType.EMBEDDING
                ? REQUIRED_EMBEDDING_DIMENSION
                : null);
        config.setBatchSize(request.batchSize());
        config.setMaxConcurrency(request.maxConcurrency());
    }

    private AiModelConfig requireEditableConfig(User user, Long id) {
        AiModelConfig config = configRepository.findById(id)
                .orElseThrow(() -> new CustomException("Model config not found", HttpStatus.NOT_FOUND));
        if (config.getOwnerType() == AiModelOwnerType.SYSTEM && user.getRole() != User.Role.ADMIN) {
            throw new CustomException("Only administrators can manage system model configs", HttpStatus.FORBIDDEN);
        }
        if (config.getOwnerType() == AiModelOwnerType.USER && !user.getId().equals(config.getOwnerUserId())) {
            throw new CustomException("Cannot modify another user's model config", HttpStatus.FORBIDDEN);
        }
        return config;
    }

    private Long validateSelectableConfig(User user, Long id, AiModelType expectedType) {
        if (id == null) {
            return null;
        }
        AiModelConfig config = configRepository.findById(id)
                .orElseThrow(() -> new CustomException("Model config not found", HttpStatus.NOT_FOUND));
        if (config.getModelType() != expectedType || !config.isEnabled() || !canView(user, config)) {
            throw new CustomException("Model config is not selectable", HttpStatus.BAD_REQUEST);
        }
        return id;
    }

    private boolean canView(User user, AiModelConfig config) {
        if (config.getOwnerType() == AiModelOwnerType.SYSTEM) {
            return config.isEnabled() || user.getRole() == User.Role.ADMIN;
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
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));
    }

    private ResolvedModelConfig toResolved(AiModelConfig config) {
        return new ResolvedModelConfig(
                config.getId(),
                config.getOwnerType(),
                config.getModelType(),
                config.getName(),
                config.getBaseUrl(),
                cryptoService.decrypt(config.getApiKeyEncrypted()),
                config.getModelName(),
                config.getTemperature(),
                config.getTopP(),
                config.getMaxTokens(),
                config.getDimension(),
                config.getBatchSize(),
                config.getMaxConcurrency());
    }

    private ResolvedModelConfig legacyLlmConfig() {
        AiProperties.Generation gen = aiProperties.getGeneration();
        return new ResolvedModelConfig(
                null,
                AiModelOwnerType.SYSTEM,
                AiModelType.LLM,
                "YAML 默认 LLM",
                trimTrailingSlash(legacyLlmBaseUrl),
                legacyLlmApiKey,
                legacyLlmModel,
                gen.getTemperature(),
                gen.getTopP(),
                gen.getMaxTokens(),
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
                trimTrailingSlash(legacyEmbeddingBaseUrl),
                legacyEmbeddingApiKey,
                legacyEmbeddingModel,
                null,
                null,
                null,
                legacyEmbeddingDimension,
                legacyEmbeddingBatchSize,
                legacyEmbeddingConcurrentEnabled ? legacyEmbeddingMaxConcurrency : 1);
    }

    private ModelConfigResponse toResponse(AiModelConfig config) {
        return new ModelConfigResponse(
                config.getId(),
                config.getOwnerType(),
                config.getOwnerUserId(),
                config.getModelType(),
                config.getName(),
                config.getProvider(),
                config.getBaseUrl(),
                maskApiKey(config.getApiKeyEncrypted()),
                config.getModelName(),
                config.isEnabled(),
                config.isDefaultModel(),
                config.getTemperature(),
                config.getTopP(),
                config.getMaxTokens(),
                config.getDimension(),
                config.getBatchSize(),
                config.getMaxConcurrency());
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

    private String trimTrailingSlash(String value) {
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
            Integer dimension,
            Integer batchSize,
            Integer maxConcurrency) {
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
            Integer dimension,
            Integer batchSize,
            Integer maxConcurrency) {
    }

    public record ModelConfigOverview(
            List<ModelConfigResponse> configs,
            Long selectedLlmConfigId,
            Long selectedEmbeddingConfigId,
            boolean admin) {
    }

    public record PreferenceRequest(Long llmConfigId, Long embeddingConfigId) {
    }

    public record PreferenceResponse(Long llmConfigId, Long embeddingConfigId) {
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
            Integer dimension,
            Integer batchSize,
            Integer maxConcurrency) {
    }
}
