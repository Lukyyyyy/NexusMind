package com.luky.nexusmind.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luky.nexusmind.service.ModelConfigService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeGraphExtractionClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private KnowledgeGraphExtractionClient client;
    private String response;
    private String nextResponse;
    private int status = 200;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requests.add(mapper.readTree(exchange.getRequestBody()));
            String body = requests.size() > 1 && nextResponse != null ? nextResponse : response;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(requests.size() > 1 && nextResponse != null ? 200 : status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        ModelConfigService configs = mock(ModelConfigService.class);
        when(configs.resolveGraphExtractionConfig("user")).thenReturn(new ModelConfigService.ResolvedModelConfig(
                1L, null, null, "test", "http://127.0.0.1:" + server.getAddress().getPort(),
                null, "test-model", null, null, 8192, null, null, null, null, null, null, null));
        client = new KnowledgeGraphExtractionClient(configs, mapper);
    }

    @AfterEach
    void cleanup() { server.stop(0); }

    private String envelope(String content, String finish) throws Exception {
        return mapper.writeValueAsString(Map.of("choices", List.of(Map.of(
                "finish_reason", finish, "message", Map.of("content", content)))));
    }

    @ParameterizedTest
    @CsvSource({"deepseek-v4-flash,true", "deepseek-v4-pro,true", "deepseek-reasoner,false",
            "deepseek-chat,false", "test-model,false"})
    void scopesLowReasoningEffortToSupportedModels(String model, boolean supported) throws Exception {
        var config = new ModelConfigService.ResolvedModelConfig(
                1L, null, null, "test", "http://127.0.0.1:" + server.getAddress().getPort(),
                null, model, null, null, 16384, null, null, null, null, null, null, null);
        response = envelope("{\"entries\":[]}", "stop");
        client.dictionaryOnce(config, "text", null, false);
        client.dictionaryOnce(config, "text", null, true);
        response = envelope("{\"relations\":[]}", "stop");
        client.relationsOnce(config, "text", null);
        assertEquals(3, requests.size());
        for (JsonNode request : requests) {
            if (supported) assertEquals("low", request.path("reasoning_effort").asText());
            else assertFalse(request.has("reasoning_effort"));
            assertEquals(16384, request.path("max_tokens").asInt());
        }
    }

    @Test
    void acceptsValidRelationsAndCodeFences() throws Exception {
        response = envelope("```json\n{\"relations\":[{\"subject\":{\"name\":\"A\",\"type\":\"MODEL\"},"
                + "\"predicate\":\"使用\",\"object\":{\"name\":\"B\",\"type\":\"METHOD\"},"
                + "\"chunkId\":1,\"evidence\":\"A 使用 B\",\"confidence\":0.9,\"valueScore\":0.8}]}\n```", "stop");
        var result = client.extract("user", "CHUNK 1: A 使用 B", null);
        assertEquals(1, result.relations().size());
        assertEquals("A", result.relations().get(0).subject().name());
        assertEquals(1, requests.size());
    }

    @ParameterizedTest
    @CsvSource({"{},缺少 relations", "null,缺少 relations", "[],缺少 relations",
            "'{\"relations\":null}',缺少 relations", "'{\"relations\":{}}',缺少 relations",
            "'{\"relations\":[1]}',数组元素格式错误", "'{',不是完整有效的 JSON"})
    void reportsInvalidStructuresAfterBoundedRetry(String content, String reason) throws Exception {
        response = envelope(content, "stop");
        var error = assertThrows(IllegalStateException.class, () -> client.extract("user", "text", null));
        assertTrue(error.getMessage().contains(reason), error.getMessage());
        assertEquals(2, requests.size());
        assertEquals(1, error.getCause().getSuppressed().length);
    }

    @Test
    void retriesTruncationWithSameBudgetAndSmallerResultLimit() throws Exception {
        response = envelope("{", "length");
        nextResponse = envelope("{\"relations\":[]}", "stop");
        assertTrue(client.extract("user", "text", null).relations().isEmpty());
        assertEquals(8192, requests.get(1).path("max_tokens").asInt());
        assertTrue(requests.get(1).path("messages").path(0).path("content").asText().contains("最多返回 10 项"));
    }

    @Test
    void distinguishesReasoningOnlyFromEmptyBody() {
        response = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":null,\"reasoning_content\":\"private reasoning\"}}]}";
        var error = assertThrows(IllegalStateException.class, () -> client.extract("user", "text", null));
        assertTrue(error.getMessage().contains("仅返回推理内容"));
        assertFalse(error.getMessage().contains("private reasoning"));
    }

    @Test
    void blankBodyIsFailureRatherThanNoRelations() throws Exception {
        response = envelope("", "stop");
        assertTrue(assertThrows(IllegalStateException.class, () -> client.extract("user", "text", null))
                .getMessage().contains("空正文"));
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            {},缺少 choices
            {,接口响应不是有效 JSON
            {"error":{}},接口返回错误对象
            """)
    void rejectsMalformedEnvelope(String body, String reason) {
        response = body;
        assertTrue(assertThrows(IllegalStateException.class, () -> client.extract("user", "text", null))
                .getMessage().contains(reason));
    }

    @Test
    void refusesTruncatedOutputEvenIfJsonLooksComplete() throws Exception {
        response = envelope("{\"relations\":[]}", "length");
        assertTrue(assertThrows(IllegalStateException.class, () -> client.extract("user", "text", null))
                .getMessage().contains("被截断"));
    }

    @Test
    void glossaryUsesSameValidationAndRetries() throws Exception {
        response = envelope("{}", "stop");
        nextResponse = envelope("{\"entities\":[]}", "stop");
        assertTrue(client.extractEntityGlossary("user", "title", "text", null).entities().isEmpty());
        assertEquals(2, requests.size());
        assertEquals(8192, requests.get(0).path("max_tokens").asInt());
    }

    @Test
    void preservesUnrelatedHttpErrorsWithoutJsonRetry() {
        status = 400;
        response = "{\"error\":\"invalid model\"}";
        assertThrows(WebClientResponseException.BadRequest.class, () -> client.extract("user", "text", null));
        assertEquals(1, requests.size());
    }

    @Test
    void fallsBackWhenResponseFormatIsUnsupported() throws Exception {
        status = 400;
        response = "{\"error\":\"unsupported response_format\"}";
        nextResponse = envelope("{\"relations\":[]}", "stop");
        assertTrue(client.extract("user", "text", null).relations().isEmpty());
        assertTrue(requests.get(0).has("response_format"));
        assertFalse(requests.get(1).has("response_format"));
    }

    @Test
    void initialDictionaryPromptDelegatesOffsetsToBackend() throws Exception {
        var config =
                new ModelConfigService.ResolvedModelConfig(
                        1L,
                        null,
                        null,
                        "test",
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        null,
                        "test-model",
                        null,
                        null,
                        8192,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        response = envelope("{\"entries\":[]}", "stop");
        client.dictionaryOnce(config, "text", "template", false);
        String prompt = requests.get(0).path("messages").path(0).path("content").asText();
        assertTrue(prompt.contains("字符位置由系统根据 evidence 计算"));
        assertTrue(prompt.contains("不要输出 start 和 end"));
        assertTrue(prompt.contains("evidence 中必须只出现一次 name"));
        assertTrue(prompt.contains("template"));
    }
}
