package com.luky.nexusmind.client;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.luky.nexusmind.config.AiProperties;
import com.luky.nexusmind.service.AiTraceService;

@Service
public class DeepSeekClient {

    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final AiProperties aiProperties;
    private final AiTraceService aiTraceService;
    private static final Logger logger = LoggerFactory.getLogger(DeepSeekClient.class);

    public DeepSeekClient(@Value("${deepseek.api.url}") String apiUrl,
            @Value("${deepseek.api.key}") String apiKey,
            @Value("${deepseek.api.model}") String model,
            AiProperties aiProperties,
            AiTraceService aiTraceService) {
        WebClient.Builder builder = WebClient.builder().baseUrl(apiUrl);

        // 只有当 API key 不为空时才添加 Authorization header
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }

        this.webClient = builder.build();
        this.apiKey = apiKey;
        this.model = model;
        this.aiProperties = aiProperties;
        this.aiTraceService = aiTraceService;
    }

    public void streamResponse(String userMessage,
            String context,
            List<Map<String, String>> history,
            String userId,
            String sessionId,
            String conversationId,
            Consumer<String> onChunk,
            Consumer<Throwable> onError,
            Runnable onComplete) {

        Map<String, Object> request = buildRequest(userMessage, context, history);
        AiTraceService.TraceSpan span = aiTraceService.startSpan(
                "llm.deepseek.stream", userId, sessionId, conversationId)
                .attribute("langfuse.observation.type", "generation")
                .attribute("langfuse.observation.model.name", model)
                .attribute("gen_ai.system", "deepseek")
                .attribute("gen_ai.request.model", model)
                .attribute("gen_ai.operation.name", "chat")
                .attribute("nexusmind.context.length", context != null ? context.length() : 0)
                .attribute("nexusmind.history.count", history != null ? history.size() : 0);

        Object temperature = request.get("temperature");
        Object topP = request.get("top_p");
        Object maxTokens = request.get("max_tokens");
        if (temperature instanceof Number value) {
            span.attribute("gen_ai.request.temperature", value.doubleValue());
        }
        if (topP instanceof Number value) {
            span.attribute("gen_ai.request.top_p", value.doubleValue());
        }
        if (maxTokens instanceof Number value) {
            span.attribute("gen_ai.request.max_tokens", value.longValue());
        }
        if (aiTraceService.shouldCaptureContent()) {
            span.attribute("gen_ai.prompt", abbreviate(userMessage, 2000));
        }

        AtomicLong responseChars = new AtomicLong();
        AtomicReference<Usage> usage = new AtomicReference<>();

        try {
            webClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .subscribe(
                            chunk -> processChunk(chunk, content -> {
                                responseChars.addAndGet(content.length());
                                onChunk.accept(content);
                            }, usage::set),
                            error -> {
                                span.attribute("gen_ai.response.output_chars", responseChars.get());
                                applyUsageAttributes(span, usage.get());
                                span.error(error);
                                span.end();
                                onError.accept(error);
                            },
                            () -> {
                                span.attribute("gen_ai.response.output_chars", responseChars.get());
                                applyUsageAttributes(span, usage.get());
                                span.end();
                                onComplete.run();
                            });
        } catch (RuntimeException e) {
            span.error(e);
            span.end();
            throw e;
        } finally {
            span.close();
        }
    }

    public String generateTitle(String userMessage, String assistantResponse) {
        String prompt = """
                请为下面这轮知识库问答生成一个 8 到 16 个汉字的会话标题。
                只输出标题，不要输出标点、解释或引号。

                用户问题：
                %s

                助手回答：
                %s
                """.formatted(abbreviate(userMessage, 800), abbreviate(assistantResponse, 800));
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("model", model);
        request.put("stream", false);
        request.put("temperature", 0.2);
        request.put("max_tokens", 32);
        request.put("messages", List.of(
                Map.of("role", "system", "content", "你是知枢 NexusMind 的会话标题生成器。"),
                Map.of("role", "user", "content", prompt)
        ));

        try {
            String response = webClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            if (response == null || response.isBlank()) {
                return null;
            }
            JsonNode node = new ObjectMapper().readTree(response);
            return node.path("choices")
                    .path(0)
                    .path("message")
                    .path("content")
                    .asText(null);
        } catch (Exception e) {
            logger.warn("生成会话标题失败，将使用兜底标题: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> buildRequest(String userMessage,
            String context,
            List<Map<String, String>> history) {
        logger.info("构建请求，用户消息：{}，上下文长度：{}，历史消息数：{}",
                userMessage,
                context != null ? context.length() : 0,
                history != null ? history.size() : 0);

        Map<String, Object> request = new java.util.HashMap<>();
        request.put("model", model);
        request.put("messages", buildMessages(userMessage, context, history));
        request.put("stream", true);
        request.put("stream_options", Map.of("include_usage", true));
        // 生成参数
        AiProperties.Generation gen = aiProperties.getGeneration();
        if (gen.getTemperature() != null) {
            request.put("temperature", gen.getTemperature());
        }
        if (gen.getTopP() != null) {
            request.put("top_p", gen.getTopP());
        }
        if (gen.getMaxTokens() != null) {
            request.put("max_tokens", gen.getMaxTokens());
        }
        return request;
    }

    private List<Map<String, String>> buildMessages(String userMessage,
            String context,
            List<Map<String, String>> history) {
        List<Map<String, String>> messages = new ArrayList<>();

        AiProperties.Prompt promptCfg = aiProperties.getPrompt();

        // 1. 构建统一的 system 指令（规则 + 参考信息）
        StringBuilder sysBuilder = new StringBuilder();
        String rules = promptCfg.getRules();
        if (rules != null) {
            sysBuilder.append(rules).append("\n\n");
        }

        String refStart = promptCfg.getRefStart() != null ? promptCfg.getRefStart() : "<<REF>>";
        String refEnd = promptCfg.getRefEnd() != null ? promptCfg.getRefEnd() : "<<END>>";
        sysBuilder.append(refStart).append("\n");

        if (context != null && !context.isEmpty()) {
            sysBuilder.append(context);
        } else {
            String noResult = promptCfg.getNoResultText() != null ? promptCfg.getNoResultText() : "（本轮无检索结果）";
            sysBuilder.append(noResult).append("\n");
        }

        sysBuilder.append(refEnd);

        String systemContent = sysBuilder.toString();
        messages.add(Map.of(
                "role", "system",
                "content", systemContent));
        logger.debug("添加了系统消息，长度: {}", systemContent.length());

        // 2. 追加历史消息（若有）
        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
        }

        // 3. 当前用户问题
        messages.add(Map.of(
                "role", "user",
                "content", userMessage));
        // logger.debug("发送给大语言模型的汇总消息: {}", messages);

        return messages;
    }

    private void processChunk(String chunk, Consumer<String> onChunk, Consumer<Usage> onUsage) {
        try {
            // 检查是否是结束标记
            if ("[DONE]".equals(chunk)) {
                logger.debug("对话结束");
                return;
            }

            // 直接解析 JSON
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(chunk);
            Usage usage = parseUsage(node.path("usage"));
            if (usage != null) {
                onUsage.accept(usage);
            }
            String content = node.path("choices")
                    .path(0)
                    .path("delta")
                    .path("content")
                    .asText("");

            if (!content.isEmpty()) {
                onChunk.accept(content);
            }
        } catch (Exception e) {
            logger.error("处理数据块时出错: {}", e.getMessage(), e);
        }
    }

    private Usage parseUsage(JsonNode usageNode) {
        if (usageNode == null || usageNode.isMissingNode() || usageNode.isNull()) {
            return null;
        }
        return new Usage(
                longOrNull(usageNode.path("prompt_tokens")),
                longOrNull(usageNode.path("completion_tokens")),
                longOrNull(usageNode.path("total_tokens")),
                longOrNull(usageNode.path("prompt_cache_hit_tokens")),
                longOrNull(usageNode.path("prompt_cache_miss_tokens")));
    }

    private void applyUsageAttributes(AiTraceService.TraceSpan span, Usage usage) {
        if (usage == null) {
            return;
        }
        if (usage.promptTokens() != null) {
            span.attribute("gen_ai.usage.prompt_tokens", usage.promptTokens());
            span.attribute("gen_ai.usage.input_tokens", usage.promptTokens());
        }
        if (usage.completionTokens() != null) {
            span.attribute("gen_ai.usage.completion_tokens", usage.completionTokens());
            span.attribute("gen_ai.usage.output_tokens", usage.completionTokens());
        }
        if (usage.totalTokens() != null) {
            span.attribute("gen_ai.usage.total_tokens", usage.totalTokens());
        }
        if (usage.promptCacheHitTokens() != null) {
            span.attribute("gen_ai.usage.prompt_cache_hit_tokens", usage.promptCacheHitTokens());
        }
        if (usage.promptCacheMissTokens() != null) {
            span.attribute("gen_ai.usage.prompt_cache_miss_tokens", usage.promptCacheMissTokens());
        }
        span.attribute("langfuse.observation.usage_details", usage.toLangfuseUsageDetailsJson());
    }

    private static Long longOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asLong();
    }

    private record Usage(Long promptTokens,
                         Long completionTokens,
                         Long totalTokens,
                         Long promptCacheHitTokens,
                         Long promptCacheMissTokens) {
        private String toLangfuseUsageDetailsJson() {
            StringBuilder json = new StringBuilder("{");
            boolean needsComma = appendJsonNumber(json, "input", promptTokens, false);
            needsComma = appendJsonNumber(json, "output", completionTokens, needsComma);
            needsComma = appendJsonNumber(json, "total", totalTokens, needsComma);
            needsComma = appendJsonNumber(json, "prompt_cache_hit", promptCacheHitTokens, needsComma);
            appendJsonNumber(json, "prompt_cache_miss", promptCacheMissTokens, needsComma);
            json.append("}");
            return json.toString();
        }

        private static boolean appendJsonNumber(StringBuilder json, String key, Long value, boolean needsComma) {
            if (value == null) {
                return needsComma;
            }
            if (needsComma) {
                json.append(",");
            }
            json.append("\"").append(key).append("\":").append(value);
            return true;
        }
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
