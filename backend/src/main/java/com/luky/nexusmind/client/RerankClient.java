package com.luky.nexusmind.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luky.nexusmind.service.AiTraceService;
import com.luky.nexusmind.service.ModelConfigService;
import com.luky.nexusmind.service.ModelUsageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DashScope 原生 rerank 客户端（qwen3-rerank / qwen3-vl-rerank 系列）。
 * 该系列接口不是 OpenAI 兼容格式：请求体为 input.query/documents + parameters 嵌套结构，
 * 响应通过 results[].index/relevance_score 返回每个文档的相关性分数。
 */
@Component
public class RerankClient {

    private static final Logger logger = LoggerFactory.getLogger(RerankClient.class);

    /** 单文档送入 rerank 的最大字符数（DashScope 单条上限 8k token，超长会被截断） */
    private static final int MAX_DOC_CHARS = 6000;

    private final ObjectMapper objectMapper;
    private final AiTraceService aiTraceService;
    private ModelUsageService modelUsageService;

    @Value("${ai.retrieval.rerank-timeout-ms:8000}")
    private long timeoutMs;

    public RerankClient(ObjectMapper objectMapper,
                        AiTraceService aiTraceService) {
        this.objectMapper = objectMapper;
        this.aiTraceService = aiTraceService;
    }

    @Autowired
    void setModelUsageService(ModelUsageService modelUsageService) { this.modelUsageService = modelUsageService; }

    /**
     * 对候选文档重排。模型配置与候选窗口由调用方（HybridSearchService）解析决定。
     *
     * @return 与 documents 顺序对齐的相关性分数；返回 null 表示 rerank 未配置或调用失败，
     *         调用方应保持融合后的原排序（降级）
     */
    public double[] rerank(String query, List<String> documents,
                           ModelConfigService.ResolvedModelConfig config, String userId) {
        if (config == null || documents == null || documents.size() < 2) {
            return null;
        }
        AiTraceService.TraceSpan span = aiTraceService.startSpan("rag.rerank", userId, null, null);
        long start = System.currentTimeMillis();
        Map<String, Object> request = buildRequestBody(query, documents, config);
        ModelUsageService.Reservation reservation = modelUsageService == null
                ? new ModelUsageService.Reservation(null, 0, 0, true)
                : modelUsageService.reserve(config, userId, "rag.rerank", request, 0);
        span.attribute("gen_ai.request.model", config.modelName())
                .attribute("nexusmind.rerank.doc_count", documents.size())
                .attribute("nexusmind.model.config.id", config.id() != null ? config.id() : -1);
        try {
            logger.debug("调用 rerank API, 模型: {}, 文档数: {}", config.modelName(), documents.size());
            String response = callApi(request, config);
            double[] scores = parseScores(response, documents.size());
            JsonNode usage = objectMapper.readTree(response).path("usage");
            if (usage.isMissingNode()) usage = objectMapper.readTree(response).path("output").path("usage");
            long tokens = usage.path("input_tokens").asLong(usage.path("total_tokens").asLong(reservation.estimatedInputTokens()));
            if (modelUsageService != null) modelUsageService.settle(reservation, tokens, 0, 0);
            span.attribute("nexusmind.rerank.took_ms", System.currentTimeMillis() - start);
            span.end();
            return scores;
        } catch (WebClientResponseException e) {
            if (modelUsageService != null) modelUsageService.release(reservation);
            // HTTP 层错误（如鉴权失败、参数不合法）标记 ERROR，便于发现配置问题
            logger.warn("rerank 调用失败(HTTP {})，保持融合排序: {}", e.getStatusCode(),
                    abbreviate(e.getResponseBodyAsString(), 300));
            span.attribute("nexusmind.rerank.degraded", true);
            span.error(e);
            span.end();
            return null;
        } catch (Exception e) {
            if (modelUsageService != null) modelUsageService.release(reservation);
            // 超时等瞬时故障属于设计内降级：不标记 ERROR，避免污染整条 trace 的状态
            logger.warn("rerank 调用超时或失败，保持融合排序: {}", e.getMessage());
            span.attribute("nexusmind.rerank.degraded", true)
                    .attribute("nexusmind.rerank.degraded_reason", e.getClass().getSimpleName());
            span.end();
            return null;
        } finally {
            span.close();
        }
    }

    private String callApi(Map<String, Object> request, ModelConfigService.ResolvedModelConfig config) {
        return buildWebClient(config).post()
                .uri(ModelConfigService.RERANK_ENDPOINT_PATH)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofMillis(timeoutMs));
    }

    /** 构造 DashScope 原生 rerank 请求体（input.query/documents + parameters 嵌套结构） */
    Map<String, Object> buildRequestBody(String query, List<String> documents,
                                         ModelConfigService.ResolvedModelConfig config) {
        Map<String, Object> input = new HashMap<>();
        input.put("query", Map.of("text", query == null ? "" : query));
        input.put("documents", documents.stream()
                .map(doc -> Map.of("text", truncate(doc)))
                .toList());
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", config.modelName());
        requestBody.put("input", input);
        requestBody.put("parameters", buildParameters(config));
        return requestBody;
    }

    /**
     * 构造可选参数。候选窗口（送入条数）由调用方控制，服务端默认返回全部已送入候选的分数，
     * 因此无需下发 top_n；未显式配置的参数一律省略，避免换用不支持该参数的模型时被服务端拒绝。
     */
    Map<String, Object> buildParameters(ModelConfigService.ResolvedModelConfig config) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("return_documents", false);
        if (config.instruct() != null && !config.instruct().isBlank()) {
            parameters.put("instruct", config.instruct());
        }
        if (config.fps() != null) {
            parameters.put("fps", config.fps());
        }
        return parameters;
    }

    private WebClient buildWebClient(ModelConfigService.ResolvedModelConfig config) {
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(config.baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (config.apiKey() != null && !config.apiKey().trim().isEmpty()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey());
        }
        return builder.build();
    }

    /**
     * 解析 rerank 响应为与输入顺序对齐的分数数组。
     * 兼容两种返回结构：顶层 results（当前 API）与 output.results（旧版本）。
     */
    double[] parseScores(String responseBody, int docCount) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            results = root.path("output").path("results");
        }
        if (!results.isArray() || results.isEmpty()) {
            throw new IllegalStateException("rerank 响应格式错误: 缺少 results 数组");
        }
        double[] scores = new double[docCount];
        int matched = 0;
        for (JsonNode item : results) {
            int index = item.path("index").asInt(-1);
            if (index >= 0 && index < docCount) {
                scores[index] = item.path("relevance_score").asDouble(0.0d);
                matched++;
            }
        }
        if (matched == 0) {
            throw new IllegalStateException("rerank 响应中没有有效的排序结果");
        }
        return scores;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_DOC_CHARS ? text : text.substring(0, MAX_DOC_CHARS);
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
