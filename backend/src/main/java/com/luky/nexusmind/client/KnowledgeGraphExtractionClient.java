package com.luky.nexusmind.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luky.nexusmind.service.ModelConfigService;
import com.luky.nexusmind.service.ModelUsageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeGraphExtractionClient {
    private static final Logger logger = LoggerFactory.getLogger(KnowledgeGraphExtractionClient.class);
    private static final String SYSTEM_PROMPT = """
            你是知枢 NexusMind 的知识图谱抽取器。抽取结果将合并到包含多篇文档的组织知识图谱中。
            请只抽取输入文本明确表达的事实，不得使用常识补充或猜测。
            实体类型优先使用 PERSON、ORGANIZATION、PROJECT、PRODUCT、SYSTEM、SERVICE、MODEL、METHOD、ALGORITHM、COMPONENT、TECHNOLOGY、DATASET、TASK、METRIC、LOCATION、EVENT、CONCEPT、DOCUMENT、OTHER。
            关系使用简短、稳定的中文动词或动宾短语，例如：属于、负责、依赖、调用、部署于、影响、参与、包含。
            每条关系必须能由一个指定的 CHUNK 直接证明。若无法确定则不要输出。
            实体名称必须脱离当前文档后仍可独立识别，并能与其他文档中的实体区分。
            严禁把“本文模型、本文方法、本研究、本系统、该模型、该方法、所提模型、提出的方法、我们的模型”等依赖当前语境的指代直接作为实体名称。
            遇到指代时，优先使用输入中提供的实体词典；否则依据文档标题和当前文本解析为有原文依据的明确名称，不得凭空创造。仍无法可靠解析时，不输出该关系。
            同一实体在本次输入的所有关系中必须使用完全一致的名称。
            实体词典只用于名称消歧和指代替换，不是实体白名单。必须继续识别切片中出现的其他明确实体。

            抽取目标不是追求数量，而是构建能支持知识检索、问答、技术比较、因果分析和决策判断的高价值事实。
            抽取时逐个检查全部 CHUNK，保留其中明确且有独立知识价值的事实：
            1. 主体和客体都可以是任何明确实体，禁止把所有关系都强行连接到论文主模型或主方法；
            2. 优先提取方法/模型的组成、依赖、输入输出、适用任务、使用数据、实验指标、比较结果、约束条件、因果影响和明确结论；
            3. 一句话包含多个事实时拆成多条关系。例如“A 使用 B 和 C”应输出 A-使用-B、A-使用-C；
            4. 非主模型关系只有在能形成可复用领域知识时才提取，例如“数据集用于任务”“模块基于算法”“方法优于基线”；
            5. 同一主谓宾只输出一次，但不同主体、不同客体或不同谓词均应保留；
            6. 不设最低数量，不要为了覆盖更多主体而凑数；单次最多输出 40 条高价值关系。

            以下内容默认没有组织知识图谱价值，禁止输出：
            - 参考文献列表、文末书目、引用格式及“本文引用某论文”；
            - 作者—发表—论文、作者—参与—论文、期刊/会议—收录—论文；
            - 仅从参考文献条目推断出的论文标题、作者、年份、页码关系；
            - 目录、页眉页脚、致谢、基金编号以及没有领域含义的文档元数据；
            - “本文进行了研究”“方法效果较好”等缺少具体对象或结论的空泛陈述。
            只有当输入正文明确讨论某项既有工作的具体方法、能力、局限或对比结果时，才可抽取该工作相关事实；仅出现于参考文献列表不算证据。
            只输出 JSON，格式为：
            {"relations":[{"subject":{"name":"","type":"SYSTEM"},"predicate":"依赖","object":{"name":"","type":"SERVICE"},"chunkId":1,"evidence":"原文中的直接证据","confidence":0.95,"valueScore":0.85}]}
            confidence 表示原文支持强度；valueScore 表示该事实进入跨文档组织图谱后的复用和决策价值，范围均为 0 到 1。
            valueScore 评分参考：能回答“如何实现、使用什么、效果多少、优于什么、受什么限制、导致什么”的具体事实通常不低于 0.7；普通背景事实约 0.5；书目和文档元数据为 0 且不得输出。
            只输出 valueScore 不低于 0.6 的关系。实体名称使用原文中最完整、最明确的名称。
            """;

    private static final String GLOSSARY_PROMPT = """
            你是知枢 NexusMind 的文档实体解析器。后续抽取结果将合并到包含多篇文档的组织知识图谱中。
            请根据文档标题和正文，为依赖当前文档语境的称呼解析可跨文档识别的标准实体名称。
            重点处理“本文模型、本文方法、本研究、本系统、该模型、该方法、所提模型、提出的方法、我们的模型”等指代，以及同一实体的简称或别名。
            标准名称必须能够脱离本文独立理解，并且必须有标题或正文依据；不得使用外部知识或凭空创造。
            无法可靠解析的指代不要输出。不要为普通名词或已经明确的专有名称创建映射。
            只输出 JSON，格式为：
            {"entities":[{"mention":"本文模型","canonicalName":"基于卷积神经网络的车辆碰撞声识别模型","type":"SYSTEM"}]}
            实体类型优先使用 PERSON、ORGANIZATION、PROJECT、PRODUCT、SYSTEM、SERVICE、MODEL、METHOD、ALGORITHM、COMPONENT、TECHNOLOGY、DATASET、TASK、METRIC、LOCATION、EVENT、CONCEPT、DOCUMENT、OTHER。
            """;

    private final ModelConfigService modelConfigService;
    private final ObjectMapper objectMapper;
    private ModelUsageService modelUsageService;

    public KnowledgeGraphExtractionClient(ModelConfigService modelConfigService, ObjectMapper objectMapper) {
        this.modelConfigService = modelConfigService;
        this.objectMapper = objectMapper;
    }

    @Autowired
    void setModelUsageService(ModelUsageService modelUsageService) { this.modelUsageService = modelUsageService; }

    public ExtractionResult extract(String username, String chunkText, String templateInstructions) {
        ModelConfigService.ResolvedModelConfig config = modelConfigService.resolveGraphExtractionConfig(username);
        String prompt = withTemplate(SYSTEM_PROMPT, templateInstructions);
        return new ExtractionResult(config.modelName(), extractItems(config, username, prompt, chunkText,
                "relations", ExtractedRelation.class, "图谱抽取"));
    }

    public EntityGlossary extractEntityGlossary(String username, String documentTitle, String documentContext,
                                                String templateInstructions) {
        ModelConfigService.ResolvedModelConfig config = modelConfigService.resolveGraphExtractionConfig(username);
        String input = "文档标题：" + documentTitle + "\n\n文档正文：\n" + documentContext;
        return new EntityGlossary(config.modelName(), extractItems(config, username,
                withTemplate(GLOSSARY_PROMPT, templateInstructions), input,
                "entities", EntityResolution.class, "实体词典"));
    }

    private <T> List<T> extractItems(ModelConfigService.ResolvedModelConfig config, String userKey, String prompt,
                                   String input, String field, Class<T> itemType, String stage) {
        // Keep the configured budget on retry; reducing it makes truncated responses more likely.
        int maxTokens = config.maxTokens() == null ? 4000 : config.maxTokens();
        InvalidModelResponse firstError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            String attemptPrompt = prompt;
            if (attempt == 2) {
                attemptPrompt += "\n\n本次为纠正重试：" + firstError.getMessage()
                        + "。仅输出包含 " + field + " 数组的完整 JSON 对象；没有结果时返回空数组。"
                        + "本次最多返回 10 项，证据保持简短，不要附加解释。";
            }
            // HTTP and transport errors are not JSON errors and must retain their original cause.
            String response = complete(config, userKey, attemptPrompt, input, maxTokens, "knowledge-graph." + stage);
            try {
                JsonNode root = responseJson(response, config.modelName(), stage, attempt, maxTokens);
                if (root == null || !root.isObject() || !root.path(field).isArray()) {
                    throw new InvalidModelResponse("模型返回的 JSON 缺少 " + field + " 数组或字段类型错误");
                }
                try {
                    return objectMapper.readerForListOf(itemType).readValue(root.path(field));
                } catch (java.io.IOException e) {
                    throw new InvalidModelResponse("模型返回的 " + field + " 数组元素格式错误", e);
                }
            } catch (InvalidModelResponse error) {
                logger.warn("{}响应校验失败: model={}, attempt={}, reason={}",
                        stage, config.modelName(), attempt, error.getMessage());
                if (firstError == null) {
                    firstError = error;
                } else {
                    error.addSuppressed(firstError);
                    throw new IllegalStateException(stage + "失败（已重试）：" + error.getMessage(), error);
                }
            }
        }
        throw new IllegalStateException("模型响应校验未完成");
    }

    public record DictionaryEntry(String name, String type, String canonicalName,
                                  Integer chunkId, Integer start, Integer end, String evidence) {}

    public List<DictionaryEntry> dictionaryOnce(ModelConfigService.ResolvedModelConfig config,
            String input, String instructions, boolean resolveConflicts) {
        return dictionaryOnce(config, null, input, instructions, resolveConflicts);
    }

    public List<DictionaryEntry> dictionaryOnce(ModelConfigService.ResolvedModelConfig config, String userKey,
            String input, String instructions, boolean resolveConflicts) {
        String prompt;
        if (resolveConflicts) {
            prompt = "根据输入候选中的原文证据解决名称映射冲突，只输出有证据支持的唯一映射；无法确定则不输出。"
                    + "\n只能选择输入中已有的完整条目，必须原样复制 name、type、canonicalName、chunkId、start、end 和 evidence，禁止修改、补全或新建条目。"
                    + "没有可靠的唯一映射时返回 {\"entries\":[]}。只输出完整 JSON，不附加解释。"
                    + "\n输出格式：{\"entries\":[{\"name\":\"SED\",\"type\":\"TASK\","
                    + "\"canonicalName\":\"声音事件检测\",\"chunkId\":1,\"start\":20,\"end\":23,\"evidence\":\"原文证据\"}]}";
        } else {
            prompt = "从每个 CHUNK 中识别需要消歧的实体称呼，只处理别名、简称，以及“本文模型、该方法、本系统、所提模型”等依赖上下文的指代。"
                    + "已经能够脱离本文独立识别的完整实体名称无需输出。"
                    + "\n只能使用文档标题、CHUNK 正文和辅助上下文，不得使用外部知识或凭空补全。"
                    + "canonicalName 必须逐字、连续地出现在文档标题或输入正文中，且不能仍是依赖上下文的模糊称呼。"
                    + "\nevidence 必须从对应 CHUNK 正文中逐字复制，是包含 name 的连续原文；evidence 中必须只出现一次 name，并应足以在该 CHUNK 中唯一定位。"
                    + "chunkId 必须是 name 实际所在的 CHUNK。字符位置由系统根据 evidence 计算，不要输出 start 和 end。"
                    + "\n任一字段、映射或证据无法确定时省略该项，禁止猜测。没有可靠映射时返回 {\"entries\":[]}。单批最多输出 15 项。"
                    + "输出前逐项检查以上条件，只输出完整 JSON，不附加解释。"
                    + "\n输出格式：{\"entries\":[{\"name\":\"SED\",\"type\":\"TASK\","
                    + "\"canonicalName\":\"声音事件检测\",\"chunkId\":1,\"evidence\":\"声音事件检测简称SED\"}]}";
        }
        return single(config, userKey, withTemplate(prompt, instructions), input, "entries", DictionaryEntry.class, "实体词典");
    }

    public ExtractionResult relationsOnce(ModelConfigService.ResolvedModelConfig config, String input, String instructions) {
        return relationsOnce(config, null, input, instructions);
    }

    public ExtractionResult relationsOnce(ModelConfigService.ResolvedModelConfig config, String userKey, String input, String instructions) {
        return new ExtractionResult(config.modelName(), single(config, userKey, withTemplate(SYSTEM_PROMPT, instructions),
                input, "relations", ExtractedRelation.class, "图谱抽取"));
    }

    private <T> List<T> single(ModelConfigService.ResolvedModelConfig config, String userKey, String prompt, String input,
                               String field, Class<T> type, String stage) {
        int budget = config.maxTokens() == null ? 16384 : config.maxTokens();
        String response = complete(config, userKey, prompt, input, budget, "knowledge-graph." + stage);
        JsonNode root = responseJson(response, config.modelName(), stage, 1, budget);
        if (root == null || !root.isObject() || !root.path(field).isArray())
            throw new InvalidModelResponse("模型返回的 JSON 缺少 " + field + " 数组或字段类型错误");
        try { return objectMapper.readerForListOf(type).readValue(root.path(field)); }
        catch (java.io.IOException e) { throw new InvalidModelResponse("模型返回的 " + field + " 元素格式错误", e); }
    }

    public static boolean isTruncated(Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause())
            if (e instanceof OutputTruncatedException) return true;
        return false;
    }

    private String complete(ModelConfigService.ResolvedModelConfig config, String userKey, String systemPrompt,
                            String userPrompt, int maxTokens, String scenario) {
        WebClient.Builder builder = WebClient.builder().baseUrl(config.baseUrl());
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey());
        }
        Map<String, Object> request = new HashMap<>();
        request.put("model", config.modelName());
        request.put("stream", false);
        request.put("temperature", 0);
        request.put("max_tokens", maxTokens);
        // Only opt in for verified V4 models; other compatible endpoints may reject this field.
        if ("deepseek-v4-flash".equals(config.modelName())
                || "deepseek-v4-pro".equals(config.modelName())) {
            request.put("reasoning_effort", "low");
        }
        request.put("response_format", Map.of("type", "json_object"));
        request.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));

        WebClient webClient = builder.build();
        String response;
        try {
            response = invokeBilled(webClient, request, config, userKey, scenario, maxTokens);
        } catch (WebClientResponseException.BadRequest unsupportedStructuredOutput) {
            String errorBody = unsupportedStructuredOutput.getResponseBodyAsString();
            if (!errorBody.contains("response_format")) throw unsupportedStructuredOutput;
            logger.info("图谱模型不接受 response_format，改用提示词约束 JSON: model={}", config.modelName());
            request.remove("response_format");
            response = invokeBilled(webClient, request, config, userKey, scenario, maxTokens);
        }
        return response;
    }

    private String withTemplate(String basePrompt, String templateInstructions) {
        if (templateInstructions == null || templateInstructions.isBlank()) return basePrompt;
        return basePrompt + "\n\n当前文档选用的抽取模板（在通用安全与输出规则基础上执行）：\n"
                + templateInstructions.trim()
                + "\n\n模板只补充领域关注点，不得改变以上证据要求、价值阈值、实体消歧、JSON 格式和禁止项；如有冲突，以上通用规则优先。";
    }

    private JsonNode responseJson(String response, String model, String stage, int attempt, int maxTokens) {
        if (response == null || response.isBlank()) throw new InvalidModelResponse("模型接口返回空响应");
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(response);
        } catch (java.io.IOException e) {
            throw new InvalidModelResponse("模型接口响应不是有效 JSON", e);
        }
        JsonNode choice = envelope.path("choices").path(0);
        JsonNode message = choice.path("message");
        JsonNode content = message.path("content");
        String finishReason = choice.path("finish_reason").asText("");
        JsonNode usage = envelope.path("usage");
        // Log only structural metadata, never document text, reasoning text, or credentials.
        logger.info("{}模型响应: model={}, attempt={}, maxTokens={}, finishReason={}, contentType={}, "
                        + "contentChars={}, reasoningChars={}, promptTokens={}, completionTokens={}, reasoningTokens={}",
                stage, model, attempt, maxTokens,
                switch (finishReason) {
                    case "stop", "length", "content_filter", "tool_calls", "function_call" -> finishReason;
                    default -> "unknown";
                }, content.getNodeType(), content.isTextual() ? content.textValue().length() : 0,
                message.path("reasoning_content").asText("").length(),
                usage.path("prompt_tokens").asInt(-1), usage.path("completion_tokens").asInt(-1),
                usage.path("completion_tokens_details").path("reasoning_tokens").asInt(-1));
        if (envelope.has("error")) throw new InvalidModelResponse("模型接口返回错误对象");
        if (choice.isMissingNode()) throw new InvalidModelResponse("模型接口响应缺少 choices 结果");
        if ("length".equals(finishReason)) {
            throw new OutputTruncatedException();
        }
        if ("content_filter".equals(finishReason) || !message.path("refusal").asText("").isBlank()) {
            throw new InvalidModelResponse("模型拒绝输出抽取结果");
        }
        if ("tool_calls".equals(finishReason) || "function_call".equals(finishReason)) {
            throw new InvalidModelResponse("模型返回了工具调用而非抽取 JSON");
        }
        if (content.isMissingNode() || content.isNull()
                || (content.isTextual() && content.textValue().isBlank())) {
            String reason = message.path("reasoning_content").asText("").isBlank()
                    ? "模型返回空正文" : "模型仅返回推理内容，没有最终 JSON 正文";
            throw new InvalidModelResponse(reason);
        }
        if (!content.isTextual()) throw new InvalidModelResponse("模型正文不是文本类型");
        try {
            return objectMapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(stripCodeFence(content.textValue()));
        } catch (java.io.IOException e) {
            throw new InvalidModelResponse("模型正文不是完整有效的 JSON", e);
        }
    }

    public static final class OutputTruncatedException extends InvalidModelResponse {
        public OutputTruncatedException() { super("模型输出达到 token 上限而被截断，请提高模型输出上限或减少抽取数量"); }
    }

    private static class InvalidModelResponse extends RuntimeException {
        InvalidModelResponse(String message) { super(message); }
        InvalidModelResponse(String message, Throwable cause) { super(message, cause); }
    }

    private String invoke(WebClient webClient, Map<String, Object> request) {
        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofMinutes(3));
    }

    private String invokeBilled(WebClient webClient, Map<String, Object> request,
                                ModelConfigService.ResolvedModelConfig config, String userKey,
                                String scenario, int maxTokens) {
        ModelUsageService.Reservation reservation = modelUsageService == null
                ? new ModelUsageService.Reservation(null, maxTokens, 0, true)
                : modelUsageService.reserve(config, userKey, scenario, request, maxTokens);
        request.put("max_tokens", reservation.allowedMaxTokens());
        try {
            String response = invoke(webClient, request);
            JsonNode usage = objectMapper.readTree(response).path("usage");
            if (modelUsageService != null) modelUsageService.settle(reservation,
                    usage.path("prompt_tokens").asLong(reservation.estimatedInputTokens()),
                    usage.path("prompt_cache_hit_tokens").asLong(0),
                    usage.path("completion_tokens").asLong(0));
            return response;
        } catch (RuntimeException e) {
            if (modelUsageService != null) modelUsageService.release(reservation);
            throw e;
        } catch (Exception e) {
            if (modelUsageService != null) modelUsageService.release(reservation);
            throw new IllegalStateException("无法解析模型用量", e);
        }
    }

    private String stripCodeFence(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!trimmed.startsWith("```")) return trimmed;
        int firstLine = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        return firstLine >= 0 && lastFence > firstLine
                ? trimmed.substring(firstLine + 1, lastFence).trim()
                : trimmed;
    }

    public record ExtractionResult(String modelName, List<ExtractedRelation> relations) {}
    public record EntityGlossary(String modelName, List<EntityResolution> entities) {}
    public record EntityResolution(String mention, String canonicalName, String type) {}
    public record ExtractedRelation(EntityValue subject, String predicate, EntityValue object,
                                    Integer chunkId, String evidence, Double confidence, Double valueScore) {}
    public record EntityValue(String name, String type) {}
}
