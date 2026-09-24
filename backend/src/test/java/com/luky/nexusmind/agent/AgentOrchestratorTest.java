package com.luky.nexusmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luky.nexusmind.client.DeepSeekClient;
import com.luky.nexusmind.client.GenerationCancellation;
import com.luky.nexusmind.agent.tool.AgentTool;
import com.luky.nexusmind.service.AiTraceService;
import com.luky.nexusmind.service.ModelConfigService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentOrchestratorTest {
    private static final AiTraceService TRACE = new AiTraceService(false, "", "", "", "test", false);

    @Test
    void answeringStepComesAfterAllToolRounds() {
        ObjectMapper objectMapper = new ObjectMapper();
        ToolCall firstCall = new ToolCall(
                "call-1", "search_knowledge_base", objectMapper.createObjectNode().put("query", "文档列表"), "{}");
        ToolCall secondCall = new ToolCall(
                "call-2", "search_knowledge_graph", objectMapper.createObjectNode().put("query", "文档关系"), "{}");
        StubDeepSeekClient client = new StubDeepSeekClient(List.of(
                new DeepSeekClient.AgentDecision(objectMapper.createObjectNode().put("role", "assistant"), List.of(firstCall)),
                new DeepSeekClient.AgentDecision(objectMapper.createObjectNode().put("role", "assistant"), List.of(secondCall)),
                new DeepSeekClient.AgentDecision(objectMapper.createObjectNode()
                        .put("role", "assistant").put("content", "最终回答"), List.of())));
        ToolRegistry tools = new ToolRegistry(List.of(), objectMapper);

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                client, tools, objectMapper, TRACE, modelConfigs(3), true, 3);
        List<AgentEvent> events = new ArrayList<>();
        List<String> chunks = new ArrayList<>();
        orchestrator.run("alice", "有哪些文档？", List.of(),
                new AgentContext("alice", 1L, "ws-1", "1", List.of(), List.of()),
                events::add, chunks::add, error -> { }, () -> { });

        assertEquals(List.of("thinking", "thinking", "call-1", "call-1", "call-2", "call-2", "answering"),
                events.stream().map(AgentEvent::stepId).toList());
        assertEquals(List.of("最终回答"), chunks);
        assertTrue(client.streamCalled);
        assertFalse(client.firstSystemPrompt.contains("企业制度"));
        assertTrue(client.firstSystemPrompt.contains("不得凭空枚举"));
    }

    @Test
    void rejectsToolCallsRenderedAsAssistantText() {
        ObjectMapper objectMapper = new ObjectMapper();
        StubDeepSeekClient client = new StubDeepSeekClient(List.of(new DeepSeekClient.AgentDecision(
                objectMapper.createObjectNode()
                        .put("role", "assistant")
                        .put("content", "我将调用 search_knowledge_base 为您查询。"),
                List.of())));
        AgentTool searchTool = new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("search_knowledge_base", "search", objectMapper.createObjectNode());
            }

            @Override
            public ToolResult execute(String callId, com.fasterxml.jackson.databind.JsonNode arguments,
                                      AgentContext context) {
                throw new AssertionError("must not execute textual tool calls");
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                client, new ToolRegistry(List.of(searchTool), objectMapper), objectMapper,
                TRACE, modelConfigs(3), true, 3);

        assertThrows(IllegalStateException.class, () -> orchestrator.run(
                "alice", "深圳今天天气怎么样？", List.of(),
                new AgentContext("alice", 1L, "ws-1", "1", List.of(), List.of()),
                event -> { }, chunk -> { }, error -> { }, () -> { }));
        assertFalse(client.streamCalled);
    }

    @Test
    void rejectsTextualToolProtocolSplitAcrossFinalStreamChunks() {
        ObjectMapper objectMapper = new ObjectMapper();
        ToolCall call = new ToolCall(
                "call-1", "search_knowledge_base", objectMapper.createObjectNode().put("query", "核心内容"), "{}");
        StubDeepSeekClient client = new StubDeepSeekClient(
                List.of(new DeepSeekClient.AgentDecision(
                        objectMapper.createObjectNode().put("role", "assistant"), List.of(call))),
                List.of("<|DS", "ML|tool_calls>"));
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                client, new ToolRegistry(List.of(), objectMapper), objectMapper, TRACE, modelConfigs(1), true, 3);
        List<AgentEvent> events = new ArrayList<>();
        List<String> chunks = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        boolean[] completed = { false };

        orchestrator.run("alice", "总结知识库", List.of(),
                new AgentContext("alice", 1L, "ws-1", "1", List.of(), List.of()),
                events::add, chunks::add, errors::add, () -> completed[0] = true);

        assertTrue(client.streamCalled);
        assertEquals("answering", events.get(events.size() - 1).stepId());
        assertTrue(chunks.isEmpty());
        assertEquals(1, errors.size());
        assertFalse(completed[0]);
    }

    @Test
    void stopsWhenAnotherRoundAddsNoSources() {
        ObjectMapper objectMapper = new ObjectMapper();
        ToolCall firstCall = new ToolCall(
                "call-1", "search_knowledge_base", objectMapper.createObjectNode().put("query", "声音事件检测"),
                "{\"query\":\"声音事件检测\"}");
        ToolCall secondCall = new ToolCall(
                "call-2", "search_knowledge_base", objectMapper.createObjectNode().put("query", "声音事件检测方法"),
                "{\"query\":\"声音事件检测方法\"}");
        StubDeepSeekClient client = new StubDeepSeekClient(List.of(
                new DeepSeekClient.AgentDecision(objectMapper.createObjectNode().put("role", "assistant"), List.of(firstCall)),
                new DeepSeekClient.AgentDecision(objectMapper.createObjectNode().put("role", "assistant"), List.of(secondCall)),
                new DeepSeekClient.AgentDecision(objectMapper.createObjectNode().put("role", "assistant"), List.of())));
        AgentTool tool = new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("search_knowledge_base", "search", objectMapper.createObjectNode());
            }

            @Override
            public ToolResult execute(String callId, com.fasterxml.jackson.databind.JsonNode arguments,
                                      AgentContext context) {
                context.allowSource("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 1);
                return new ToolResult(callId, definition().name(), objectMapper.createObjectNode(), true, 1);
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                client, new ToolRegistry(List.of(tool), objectMapper), objectMapper, TRACE, modelConfigs(3), true, 3);

        orchestrator.run("alice", "总结知识库", List.of(),
                new AgentContext("alice", 1L, "ws-1", "1", List.of(), List.of()),
                event -> { }, chunk -> { }, error -> { }, () -> { });

        assertEquals(2, client.decisionIndex);
        assertTrue(client.streamCalled);
    }

    @Test
    void cancellationStopsBeforeModelOrToolsRun() {
        ObjectMapper objectMapper = new ObjectMapper();
        StubDeepSeekClient client = new StubDeepSeekClient(List.of());
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                client, new ToolRegistry(List.of(), objectMapper), objectMapper, TRACE, modelConfigs(3), true, 3);
        GenerationCancellation cancellation = new GenerationCancellation();
        cancellation.cancel();
        boolean[] completed = { false };

        orchestrator.run("alice", "总结知识库", List.of(),
                new AgentContext("alice", 1L, "ws-1", "1", List.of(), List.of()), cancellation,
                event -> { }, chunk -> { }, error -> { }, () -> completed[0] = true);

        assertEquals(0, client.decisionIndex);
        assertFalse(client.streamCalled);
        assertTrue(completed[0]);
    }

    @Test
    void enforcesTotalToolBudgetWithoutPublishingRejectedCalls() {
        ObjectMapper objectMapper = new ObjectMapper();
        List<ToolCall> calls = List.of(1, 2, 3).stream()
                .map(index -> new ToolCall("call-" + index, "search_knowledge_base",
                        objectMapper.createObjectNode().put("query", "query-" + index),
                        "{\"query\":\"query-" + index + "\"}"))
                .toList();
        StubDeepSeekClient client = new StubDeepSeekClient(List.of(new DeepSeekClient.AgentDecision(
                objectMapper.createObjectNode().put("role", "assistant"), calls)));
        AtomicInteger executions = new AtomicInteger();
        AgentTool tool = new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("search_knowledge_base", "search", objectMapper.createObjectNode());
            }

            @Override
            public ToolResult execute(String callId, com.fasterxml.jackson.databind.JsonNode arguments,
                                      AgentContext context) {
                executions.incrementAndGet();
                return new ToolResult(callId, definition().name(), objectMapper.createObjectNode(), true, 1);
            }
        };
        List<AgentEvent> events = new ArrayList<>();
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                client, new ToolRegistry(List.of(tool), objectMapper), objectMapper,
                TRACE, modelConfigs(2), true, 6);

        orchestrator.run("alice", "search", List.of(),
                new AgentContext("alice", 1L, "ws-1", "1", List.of(), List.of()),
                events::add, chunk -> { }, error -> { }, () -> { });

        assertEquals(2, executions.get());
        assertEquals(4, events.stream().filter(event -> event.stepId().startsWith("call-")).count());
        assertTrue(client.lastDecisionMessages.get(0).get("content")
                .toString().contains("剩余 2 次"));
        assertTrue(client.lastStreamMessages.stream()
                .anyMatch(message -> String.valueOf(message.get("content")).contains("TOOL_CALL_LIMIT")));
    }

    @Test
    void runsUpToFourIndependentCallsTogetherAndPreservesResponseOrder() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<ToolCall> calls = List.of(1, 2, 3, 4, 5).stream()
                .map(i -> new ToolCall("call-" + i, "search_knowledge_base",
                        mapper.createObjectNode().put("query", "query-" + i), "query-" + i))
                .toList();
        StubDeepSeekClient client = new StubDeepSeekClient(List.of(new DeepSeekClient.AgentDecision(
                mapper.createObjectNode().put("role", "assistant"), calls)));
        CountDownLatch fourStarted = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AgentTool tool = new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("search_knowledge_base", "search", mapper.createObjectNode());
            }

            @Override
            public ToolResult execute(String callId, com.fasterxml.jackson.databind.JsonNode arguments,
                                      AgentContext context) {
                int running = active.incrementAndGet();
                maxActive.accumulateAndGet(running, Math::max);
                try {
                    if (started.incrementAndGet() <= 4) {
                        fourStarted.countDown();
                        if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("tools did not overlap");
                    }
                    return new ToolResult(callId, definition().name(), mapper.createObjectNode(), true, 1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                } finally {
                    active.decrementAndGet();
                }
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                client, new ToolRegistry(List.of(tool), mapper), mapper, TRACE, modelConfigs(5), true, 5);
        CompletableFuture<Void> run = CompletableFuture.runAsync(() -> orchestrator.run(
                "alice", "search", List.of(), new AgentContext("alice", 1L, "ws-1", "1"),
                event -> { }, chunk -> { }, error -> { }, () -> { }));

        try {
            assertTrue(fourStarted.await(2, TimeUnit.SECONDS));
            assertEquals(4, active.get());
        } finally {
            release.countDown();
        }
        run.get(3, TimeUnit.SECONDS);
        assertEquals(4, maxActive.get());
        assertEquals(List.of("call-1", "call-2", "call-3", "call-4", "call-5"),
                client.lastStreamMessages.stream()
                        .filter(message -> "tool".equals(message.get("role")))
                        .map(message -> (String) message.get("tool_call_id"))
                        .toList());
    }

    @Test
    void waitsForSameRoundSourceProducingCallsBeforeReadingChunkContext() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String md5 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        ToolCall chunk = new ToolCall("chunk", "get_chunk_context",
                mapper.createObjectNode().put("fileMd5", md5).put("chunkId", 1), "chunk");
        ToolCall nextChunk = new ToolCall("next-chunk", "get_chunk_context",
                mapper.createObjectNode().put("fileMd5", md5).put("chunkId", 2), "next-chunk");
        ToolCall search = new ToolCall("search", "search_knowledge_base",
                mapper.createObjectNode().put("query", "topic"), "search");
        StubDeepSeekClient client = new StubDeepSeekClient(List.of(new DeepSeekClient.AgentDecision(
                mapper.createObjectNode().put("role", "assistant"), List.of(chunk, nextChunk, search))));
        CountDownLatch searchStarted = new CountDownLatch(1);
        CountDownLatch releaseSearch = new CountDownLatch(1);
        AtomicInteger contextCalls = new AtomicInteger();
        AgentTool searchTool = new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("search_knowledge_base", "search", mapper.createObjectNode());
            }

            @Override
            public ToolResult execute(String callId, com.fasterxml.jackson.databind.JsonNode arguments,
                                      AgentContext context) {
                searchStarted.countDown();
                try {
                    if (!releaseSearch.await(3, TimeUnit.SECONDS)) throw new AssertionError("search stalled");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                context.allowSource(md5, 1);
                return new ToolResult(callId, definition().name(), mapper.createObjectNode(), true, 1);
            }
        };
        AgentTool chunkTool = new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("get_chunk_context", "context", mapper.createObjectNode());
            }

            @Override
            public ToolResult execute(String callId, com.fasterxml.jackson.databind.JsonNode arguments,
                                      AgentContext context) {
                contextCalls.incrementAndGet();
                int chunkId = arguments.path("chunkId").asInt();
                if (!context.isSourceAllowed(md5, chunkId)) throw new IllegalArgumentException("source not ready");
                context.allowSource(md5, chunkId + 1);
                return new ToolResult(callId, definition().name(), mapper.createObjectNode(), true, 1);
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                client, new ToolRegistry(List.of(searchTool, chunkTool), mapper), mapper,
                TRACE, modelConfigs(3), true, 3);
        CompletableFuture<Void> run = CompletableFuture.runAsync(() -> orchestrator.run(
                "alice", "search", List.of(), new AgentContext("alice", 1L, "ws-1", "1"),
                event -> { }, chunkText -> { }, error -> { }, () -> { }));

        try {
            assertTrue(searchStarted.await(2, TimeUnit.SECONDS));
            assertEquals(0, contextCalls.get());
        } finally {
            releaseSearch.countDown();
        }
        run.get(3, TimeUnit.SECONDS);
        assertEquals(2, contextCalls.get());
        assertTrue(client.lastStreamMessages.stream()
                .filter(message -> "tool".equals(message.get("role")))
                .noneMatch(message -> String.valueOf(message.get("content")).contains("TOOL_FAILED")));
    }

    @Test
    void cancellationWhileToolsRunStopsBeforeAnswering() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ToolCall call = new ToolCall("call-1", "search_knowledge_base",
                mapper.createObjectNode().put("query", "topic"), "topic");
        StubDeepSeekClient client = new StubDeepSeekClient(List.of(new DeepSeekClient.AgentDecision(
                mapper.createObjectNode().put("role", "assistant"), List.of(call))));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AgentTool tool = new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("search_knowledge_base", "search", mapper.createObjectNode());
            }

            @Override
            public ToolResult execute(String callId, com.fasterxml.jackson.databind.JsonNode arguments,
                                      AgentContext context) {
                started.countDown();
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("tool stalled");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return new ToolResult(callId, definition().name(), mapper.createObjectNode(), true, 1);
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                client, new ToolRegistry(List.of(tool), mapper), mapper, TRACE, modelConfigs(1), true, 1);
        GenerationCancellation cancellation = new GenerationCancellation();
        AtomicInteger completions = new AtomicInteger();
        CompletableFuture<Void> run = CompletableFuture.runAsync(() -> orchestrator.run(
                "alice", "search", List.of(), new AgentContext("alice", 1L, "ws-1", "1"), cancellation,
                event -> { }, chunk -> { }, error -> { }, completions::incrementAndGet));

        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            cancellation.cancel();
            run.get(2, TimeUnit.SECONDS);
            assertEquals(1, completions.get());
            assertFalse(client.streamCalled);
        } finally {
            release.countDown();
        }
    }

    private static ModelConfigService modelConfigs(int maxToolCalls) {
        return new ModelConfigService(
                null, null, null, null, null, null,
                "", "", "", "", "", "", 1, false, 1, 2048, 30) {
            @Override
            public ResolvedModelConfig resolveLlmConfig(String username) {
                return new ResolvedModelConfig(
                        1L, null, null, "test", "http://test", null, "test",
                        null, null, null, maxToolCalls, null, null, null, null, null, null);
            }
        };
    }

    private static final class StubDeepSeekClient extends DeepSeekClient {
        private final List<AgentDecision> decisions;
        private final List<String> streamChunks;
        private int decisionIndex;
        private boolean streamCalled;
        private String firstSystemPrompt;
        private List<Map<String, Object>> lastDecisionMessages;
        private List<Map<String, Object>> lastStreamMessages;

        private StubDeepSeekClient(List<AgentDecision> decisions) {
            this(decisions, List.of("最终回答"));
        }

        private StubDeepSeekClient(List<AgentDecision> decisions, List<String> streamChunks) {
            super(null, null, null);
            this.decisions = decisions;
            this.streamChunks = streamChunks;
        }

        @Override
        public AgentDecision callWithTools(ModelConfigService.ResolvedModelConfig modelConfig,
                                           List<Map<String, Object>> messages,
                                           List<ToolDefinition> tools,
                                           String userId,
                                           String sessionId,
                                           String conversationId,
                                           GenerationCancellation cancellation) {
            if (firstSystemPrompt == null) firstSystemPrompt = String.valueOf(messages.get(0).get("content"));
            lastDecisionMessages = List.copyOf(messages);
            return decisions.get(decisionIndex++);
        }

        @Override
        public void streamAgentResponse(ModelConfigService.ResolvedModelConfig modelConfig,
                                        List<Map<String, Object>> messages,
                                        List<ToolDefinition> tools,
                                        String userId,
                                        String sessionId,
                                        String conversationId,
                                        GenerationCancellation cancellation,
                                        Consumer<String> onChunk,
                                        Consumer<Throwable> onError,
                                        Runnable onComplete) {
            streamCalled = true;
            lastStreamMessages = List.copyOf(messages);
            streamChunks.forEach(onChunk);
            onComplete.run();
        }
    }
}
