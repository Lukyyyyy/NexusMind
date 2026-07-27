package com.luky.nexusmind.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luky.nexusmind.service.ModelConfigService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeGraphExtractionClient {
    private static final String SYSTEM_PROMPT = """
            你是知枢 NexusMind 的知识图谱抽取器。请只抽取输入文本明确表达的事实，不得使用常识补充或猜测。
            实体类型优先使用 PERSON、ORGANIZATION、PROJECT、PRODUCT、SYSTEM、SERVICE、TECHNOLOGY、LOCATION、EVENT、CONCEPT、DOCUMENT、OTHER。
            关系使用简短、稳定的中文动词或动宾短语，例如：属于、负责、依赖、调用、部署于、影响、参与、包含。
            每条关系必须能由一个指定的 CHUNK 直接证明。若无法确定则不要输出。
            只输出 JSON，格式为：
            {"relations":[{"subject":{"name":"","type":"SYSTEM"},"predicate":"依赖","object":{"name":"","type":"SERVICE"},"chunkId":1,"evidence":"原文中的直接证据","confidence":0.95}]}
            confidence 范围为 0 到 1。实体名称使用原文中最完整、最明确的名称。
            """;

    private final ModelConfigService modelConfigService;
    private final ObjectMapper objectMapper;

    public KnowledgeGraphExtractionClient(ModelConfigService modelConfigService, ObjectMapper objectMapper) {
        this.modelConfigService = modelConfigService;
        this.objectMapper = objectMapper;
    }

    public ExtractionResult extract(String username, String chunkText) {
        ModelConfigService.ResolvedModelConfig config = modelConfigService.resolveGraphExtractionConfig(username);
        WebClient.Builder builder = WebClient.builder().baseUrl(config.baseUrl());
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey());
        }
        Map<String, Object> request = new HashMap<>();
        request.put("model", config.modelName());
        request.put("stream", false);
        request.put("temperature", 0);
        request.put("max_tokens", Math.max(1000, config.maxTokens() == null ? 2000 : config.maxTokens()));
        request.put("response_format", Map.of("type", "json_object"));
        request.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", chunkText)));

        WebClient webClient = builder.build();
        String response;
        try {
            response = invoke(webClient, request);
        } catch (WebClientResponseException.BadRequest unsupportedStructuredOutput) {
            // Some OpenAI-compatible providers do not implement response_format yet.
            request.remove("response_format");
            response = invoke(webClient, request);
        }
        if (response == null || response.isBlank()) return new ExtractionResult(config.modelName(), List.of());
        try {
            String content = objectMapper.readTree(response).path("choices").path(0).path("message").path("content").asText();
            JsonNode root = objectMapper.readTree(stripCodeFence(content));
            List<ExtractedRelation> relations = objectMapper.readerForListOf(ExtractedRelation.class)
                    .readValue(root.path("relations"));
            return new ExtractionResult(config.modelName(), relations == null ? List.of() : relations);
        } catch (Exception e) {
            throw new IllegalStateException("图谱抽取模型返回了无效数据", e);
        }
    }

    private String invoke(WebClient webClient, Map<String, Object> request) {
        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class)
                .block();
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
    public record ExtractedRelation(EntityValue subject, String predicate, EntityValue object,
                                    Integer chunkId, String evidence, Double confidence) {}
    public record EntityValue(String name, String type) {}
}
