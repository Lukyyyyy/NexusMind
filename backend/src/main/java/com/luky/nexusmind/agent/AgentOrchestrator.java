package com.luky.nexusmind.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luky.nexusmind.client.DeepSeekClient;
import com.luky.nexusmind.client.GenerationCancellation;
import com.luky.nexusmind.service.AiTraceService;
import com.luky.nexusmind.service.ModelConfigService;
import com.luky.nexusmind.utils.PromptLoader;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

@Service
public class AgentOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);
    private static final String SYSTEM_PROMPT = PromptLoader.load("agent-system.md");
    // ponytail: shared four-thread pool has an unbounded queue; add backpressure if concurrent chats saturate it.
    private static final ExecutorService TOOL_EXECUTOR = Executors.newFixedThreadPool(4, task -> {
        Thread thread = new Thread(task, "agent-tool");
        thread.setDaemon(true);
        return thread;
    });

    private final DeepSeekClient deepSeekClient;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final AiTraceService aiTraceService;
    private final ModelConfigService modelConfigService;
    private final boolean enabled;
    private final int defaultMaxToolCalls;

    public AgentOrchestrator(DeepSeekClient deepSeekClient,
                             ToolRegistry toolRegistry,
                             ObjectMapper objectMapper,
                             AiTraceService aiTraceService,
                             ModelConfigService modelConfigService,
                             @Value("${ai.agent.tool-calling-enabled:true}") boolean enabled,
                             @Value("${ai.agent.max-tool-calls:10}") int defaultMaxToolCalls) {
        this.deepSeekClient = deepSeekClient;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.aiTraceService = aiTraceService;
        this.modelConfigService = modelConfigService;
        this.enabled = enabled;
        this.defaultMaxToolCalls = clampToolCallLimit(defaultMaxToolCalls);
    }

    public boolean isEnabled() { return enabled; }

    public void run(String configUsername,
                    String userMessage,
                    List<Map<String, String>> history,
                    AgentContext context,
                    Consumer<AgentEvent> onEvent,
                    Consumer<String> onChunk,
                    Consumer<Throwable> onError,
                    Runnable onComplete) {
        run(configUsername, userMessage, history, context, new GenerationCancellation(),
                onEvent, onChunk, onError, onComplete);
    }

    public void run(String configUsername,
                    String userMessage,
                    List<Map<String, String>> history,
                    AgentContext context,
                    GenerationCancellation cancellation,
                    Consumer<AgentEvent> onEvent,
                    Consumer<String> onChunk,
                    Consumer<Throwable> onError,
                    Runnable onComplete) {
        run(modelConfigService.resolveLlmConfig(configUsername), userMessage, history, context, cancellation,
                onEvent, onChunk, onError, onComplete);
    }

    public void run(ModelConfigService.ResolvedModelConfig modelConfig,
                    String userMessage,
                    List<Map<String, String>> history,
                    AgentContext context,
                    GenerationCancellation cancellation,
                    Consumer<AgentEvent> onEvent,
                    Consumer<String> onChunk,
                    Consumer<Throwable> onError,
                    Runnable onComplete) {
        List<Map<String, Object>> messages = initialMessages(history, userMessage);
        Set<String> executedCalls = new HashSet<>();
        DeepSeekClient.AgentDecision finalDecision = null;
        int roundsWithoutNewSources = 0;
        int requestedToolCalls = 0;
        // 每次回答开始时快照配置，避免编辑模型配置改变正在进行的 Agent 行为。
        Integer configuredLimit = modelConfig.maxToolCalls();
        int maxToolCalls = clampToolCallLimit(configuredLimit != null ? configuredLimit : defaultMaxToolCalls);
        if (finishIfCancelled(cancellation, onComplete)) return;
        onEvent.accept(AgentEvent.thinking());

        while (requestedToolCalls < maxToolCalls) {
            if (finishIfCancelled(cancellation, onComplete)) return;
            DeepSeekClient.AgentDecision decision;
            try {
                decision = deepSeekClient.callWithTools(
                        modelConfig, withToolBudget(messages, maxToolCalls, requestedToolCalls),
                        toolRegistry.definitions(), context.traceUserId(),
                        context.websocketSessionId(), String.valueOf(context.chatSessionId()), cancellation);
            } catch (CancellationException ignored) {
                onComplete.run();
                return;
            }
            if (finishIfCancelled(cancellation, onComplete)) return;
            if (requestedToolCalls == 0) onEvent.accept(AgentEvent.thinkingCompleted(!decision.toolCalls().isEmpty()));
            if (decision.toolCalls().isEmpty()) {
                finalDecision = decision;
                break;
            }

            messages.add(objectMapper.convertValue(decision.assistantMessage(), new TypeReference<>() {}));
            int executedThisRound = 0;
            int sourcesBeforeRound = context.allowedSourceCount();
            List<ToolCall> calls = decision.toolCalls();
            List<CompletableFuture<ToolExecution>> results = new ArrayList<>(Collections.nCopies(calls.size(), null));
            List<CompletableFuture<ToolExecution>> searches = new ArrayList<>();
            List<Integer> contexts = new ArrayList<>();
            Context traceContext = Context.current();
            int allowedThisRound = maxToolCalls - requestedToolCalls;
            try {
                for (int i = 0; i < calls.size(); i++) {
                    if (cancellation.isCancelled()) throw new CancellationException();
                    ToolCall call = calls.get(i);
                    if (requestedToolCalls++ >= maxToolCalls) {
                        results.set(i, CompletableFuture.completedFuture(new ToolExecution(limitedResult(call), 0)));
                        continue;
                    }
                    onEvent.accept(AgentEvent.toolStarted(call));
                    if (!executedCalls.add(call.name() + ":" + call.rawArguments())) {
                        results.set(i, CompletableFuture.completedFuture(new ToolExecution(duplicateResult(call), 0)));
                    } else {
                        executedThisRound++;
                        if ("get_chunk_context".equals(call.name())) {
                            contexts.add(i);
                        } else {
                            CompletableFuture<ToolExecution> result = CompletableFuture.supplyAsync(
                                    () -> executeTool(call, context, traceContext, cancellation), TOOL_EXECUTOR);
                            results.set(i, result);
                            if ("search_knowledge_base".equals(call.name())
                                    || "search_knowledge_graph".equals(call.name())) searches.add(result);
                        }
                    }
                }
                CompletableFuture<Void> sourcesReady = CompletableFuture.allOf(searches.toArray(CompletableFuture[]::new));
                for (int i : contexts) {
                    ToolCall call = calls.get(i);
                    CompletableFuture<ToolExecution> result = sourcesReady.thenApplyAsync(
                            ignored -> executeTool(call, context, traceContext, cancellation), TOOL_EXECUTOR);
                    results.set(i, result);
                    // Later context calls may need sources returned by earlier context calls.
                    sourcesReady = result.thenApply(ignored -> null);
                }
                for (int i = 0; i < calls.size(); i++) {
                    ToolExecution execution = awaitTool(results.get(i), cancellation);
                    if (i < allowedThisRound) {
                        ToolCall call = calls.get(i);
                        onEvent.accept(AgentEvent.toolCompleted(call, execution.result().resultCount(),
                                execution.durationMs(), execution.result().success()));
                    }
                    addToolMessage(messages, calls.get(i), execution.result());
                }
            } catch (RuntimeException e) {
                results.stream().filter(result -> result != null).forEach(result -> result.cancel(true));
                if (cancellation.isCancelled()) {
                    onComplete.run();
                    return;
                }
                throw e;
            }

            if (requestedToolCalls >= maxToolCalls) break;
            if (executedThisRound == 0) break;
            if (context.allowedSourceCount() == sourcesBeforeRound) {
                roundsWithoutNewSources++;
                if (sourcesBeforeRound > 0 || roundsWithoutNewSources >= 2) break;
            } else {
                roundsWithoutNewSources = 0;
            }
        }

        if (finishIfCancelled(cancellation, onComplete)) return;
        if (finalDecision != null) rejectTextualToolCall(finalDecision);
        messages.add(new LinkedHashMap<>(Map.of("role", "system", "content",
                "工具调用已结束。只能根据已有工具结果回答；不得请求、调用或描述任何工具，资料不足时直接说明。")));
        onEvent.accept(AgentEvent.answering());
        StreamingProtocolGuard guard = new StreamingProtocolGuard(
                toolRegistry.definitions().stream().map(ToolDefinition::name).toList(), onChunk, onError);
        deepSeekClient.streamAgentResponse(
                modelConfig, messages, toolRegistry.definitions(), context.traceUserId(),
                context.websocketSessionId(), String.valueOf(context.chatSessionId()),
                cancellation, guard::accept, onError,
                () -> {
                    if (cancellation.isCancelled()) onComplete.run();
                    else guard.complete(onComplete);
                });
    }

    private boolean finishIfCancelled(GenerationCancellation cancellation, Runnable onComplete) {
        if (!cancellation.isCancelled()) return false;
        onComplete.run();
        return true;
    }

    private record ToolExecution(ToolResult result, long durationMs) {}

    private ToolExecution executeTool(ToolCall call, AgentContext context, Context traceContext,
                                      GenerationCancellation cancellation) {
        if (cancellation.isCancelled()) throw new CancellationException();
        try (Scope ignored = traceContext.makeCurrent()) {
            AiTraceService.TraceSpan toolSpan = aiTraceService.startSpan(
                    "agent.tool.execute", context.traceUserId(), null, null)
                    .attribute("nexusmind.agent.tool.name", call.name())
                    .attribute("nexusmind.agent.tool.call_id", call.id());
            long started = System.nanoTime();
            try {
                ToolResult result = toolRegistry.execute(call, context);
                long durationMs = (System.nanoTime() - started) / 1_000_000;
                toolSpan.attribute("nexusmind.agent.tool.success", result.success())
                        .attribute("nexusmind.agent.tool.result_count", result.resultCount())
                        .attribute("nexusmind.agent.tool.took_ms", durationMs);
                if (aiTraceService.shouldCaptureContent()) {
                    String toolOutput = summarizeToolOutput(result.content());
                    toolSpan.attribute("input.value", abbreviate(call.rawArguments(), 2000))
                            .attribute("langfuse.observation.input", abbreviate(call.rawArguments(), 2000))
                            .attribute("output.value", toolOutput)
                            .attribute("langfuse.observation.output", toolOutput);
                }
                return new ToolExecution(result, durationMs);
            } catch (RuntimeException e) {
                toolSpan.error(e);
                throw e;
            } finally {
                toolSpan.end();
                toolSpan.close();
            }
        }
    }

    private ToolExecution awaitTool(CompletableFuture<ToolExecution> result, GenerationCancellation cancellation) {
        while (true) {
            if (cancellation.isCancelled()) throw new CancellationException();
            try {
                return result.get(100, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // Check cancellation while a synchronous tool is still running.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException("工具执行被中断");
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException runtime) throw runtime;
                if (e.getCause() instanceof Error error) throw error;
                throw new IllegalStateException("工具执行失败", e.getCause());
            }
        }
    }

    private List<Map<String, Object>> initialMessages(List<Map<String, String>> history, String userMessage) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(new LinkedHashMap<>(Map.of("role", "system", "content", SYSTEM_PROMPT)));
        if (history != null) {
            for (Map<String, String> message : history) messages.add(new LinkedHashMap<>(message));
        }
        messages.add(new LinkedHashMap<>(Map.of("role", "user", "content", userMessage)));
        return messages;
    }

    private List<Map<String, Object>> withToolBudget(List<Map<String, Object>> messages, int limit, int used) {
        List<Map<String, Object>> decisionMessages = new ArrayList<>(messages);
        Map<String, Object> systemMessage = new LinkedHashMap<>(decisionMessages.get(0));
        systemMessage.put("content", systemMessage.get("content") + "\n\n" + String.format(
                "工具调用额度由系统精确计数：本次回答最多 %d 次，已请求 %d 次，剩余 %d 次。"
                        + "本次返回的 tool_calls 数量不得超过剩余额度；额度用完后根据已有资料回答。",
                limit, used, limit - used));
        decisionMessages.set(0, systemMessage);
        return decisionMessages;
    }

    private void addToolMessage(List<Map<String, Object>> messages, ToolCall call, ToolResult result) {
        Map<String, Object> toolMessage = new LinkedHashMap<>();
        toolMessage.put("role", "tool");
        toolMessage.put("tool_call_id", call.id());
        toolMessage.put("name", call.name());
        toolMessage.put("content", result.content().toString());
        messages.add(toolMessage);
    }

    private static int clampToolCallLimit(int value) {
        return Math.max(1, Math.min(20, value));
    }

    private ToolResult duplicateResult(ToolCall call) {
        var content = objectMapper.createObjectNode();
        content.put("status", "error");
        content.put("code", "DUPLICATE_CALL");
        content.put("message", "相同参数的工具调用已经执行过，请使用已有结果");
        log.debug("跳过重复 Agent 工具调用: {}", call.name());
        return new ToolResult(call.id(), call.name(), content, false, 0);
    }

    private ToolResult limitedResult(ToolCall call) {
        var content = objectMapper.createObjectNode();
        content.put("status", "error");
        content.put("code", "TOOL_CALL_LIMIT");
        content.put("message", "本次回答的工具调用额度已用完，请使用已有结果回答");
        return new ToolResult(call.id(), call.name(), content, false, 0);
    }

    private void rejectTextualToolCall(DeepSeekClient.AgentDecision decision) {
        String content = decision.assistantMessage().path("content").asText("");
        if (!decision.toolCalls().isEmpty()
                || content.contains("<|DSML|")
                || content.contains("<tool_call")
                || toolRegistry.definitions().stream().anyMatch(tool -> content.contains(tool.name()))) {
            throw new IllegalStateException("模型未使用原生 Tool Calling 协议");
        }
    }

    static final class StreamingProtocolGuard {
        private final List<String> forbidden;
        private final Consumer<String> downstream;
        private final Consumer<Throwable> onError;
        private final StringBuilder pending = new StringBuilder();
        private final int tailLength;
        private boolean failed;

        StreamingProtocolGuard(List<String> toolNames,
                               Consumer<String> downstream,
                               Consumer<Throwable> onError) {
            // ponytail: guard known text protocols; replace with typed Responses events when the provider supports them.
            List<String> values = new ArrayList<>(List.of("<|dsml|", "<tool_call"));
            values.addAll(toolNames.stream().map(name -> name.toLowerCase(Locale.ROOT)).toList());
            forbidden = List.copyOf(values);
            this.downstream = downstream;
            this.onError = onError;
            tailLength = forbidden.stream().mapToInt(String::length).max().orElse(1) - 1;
        }

        void accept(String chunk) {
            if (failed || chunk == null || chunk.isEmpty()) return;
            pending.append(chunk);
            String buffered = pending.toString().toLowerCase(Locale.ROOT);
            if (forbidden.stream().anyMatch(buffered::contains)) {
                failed = true;
                pending.setLength(0);
                onError.accept(new IllegalStateException("模型输出了内部工具协议"));
                return;
            }
            int flushLength = pending.length() - tailLength;
            if (flushLength <= 0) return;
            downstream.accept(pending.substring(0, flushLength));
            pending.delete(0, flushLength);
        }

        void complete(Runnable onComplete) {
            if (failed) return;
            if (!pending.isEmpty()) downstream.accept(pending.toString());
            pending.setLength(0);
            onComplete.run();
        }
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "…";
    }

    /**
     * 工具输出的可读摘要：保留 status/query 等标量字段，sources 每条只留来源定位信息 + 200 字内容预览，
     * 输出为格式化 JSON（原先直接截断压缩 JSON 会导致前端拿到断掉的非法 JSON 无法排版）。
     */
    private String summarizeToolOutput(JsonNode content) {
        try {
            if (content == null || !content.isObject()) {
                return abbreviate(String.valueOf(content), 4000);
            }
            ObjectNode summary = objectMapper.createObjectNode();
            for (Map.Entry<String, JsonNode> field : content.properties()) {
                if ("sources".equals(field.getKey()) || "documents".equals(field.getKey())) continue;
                summary.set(field.getKey(), field.getValue().deepCopy());
            }
            summarizeEntries(summary, content, "sources", 10);
            summarizeEntries(summary, content, "documents", 10);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary);
        } catch (Exception e) {
            return abbreviate(String.valueOf(content), 4000);
        }
    }

    /**
     * 将 content 中指定数组字段的摘要写入 summary：保留定位字段（sourceId/fileMd5/fileName 等）与内容预览，
     * 超出 limit 的条目以 xxx_omitted 计数标注。
     */
    private void summarizeEntries(ObjectNode summary, JsonNode content, String field, int limit) {
        JsonNode entries = content.path(field);
        if (!entries.isArray() || entries.isEmpty()) return;
        ArrayNode summarized = summary.putArray(field);
        int kept = Math.min(entries.size(), limit);
        for (int i = 0; i < kept; i++) {
            JsonNode entry = entries.get(i);
            ObjectNode node = summarized.addObject();
            for (String key : new String[] {"sourceId", "fileMd5", "fileName", "chunkId", "score",
                    "orgTag", "isPublic", "sizeBytes", "uploadedAt"}) {
                if (entry.hasNonNull(key)) node.set(key, entry.get(key).deepCopy());
            }
            String text = entry.path("content").asText("");
            if (!text.isEmpty()) node.put("content", text.length() > 200 ? text.substring(0, 200) + "…" : text);
        }
        if (entries.size() > kept) {
            summary.put(field + "_omitted", entries.size() - kept);
        }
    }
}
