package com.luky.nexusmind.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luky.nexusmind.model.AiModelOwnerType;
import com.luky.nexusmind.model.AiModelType;
import com.luky.nexusmind.service.AiTraceService;
import com.luky.nexusmind.service.ModelConfigService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RerankClientTest {

    private final RerankClient client = new RerankClient(
            new ObjectMapper(),
            new AiTraceService(false, "", "", "", "test", false));

    @Test
    void parsesTopLevelResultsArray() throws Exception {
        String body = "{\"results\":[{\"index\":2,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.5}]}";

        double[] scores = client.parseScores(body, 3);

        assertArrayEquals(new double[]{0.5d, 0.0d, 0.9d}, scores, 1e-9);
    }

    @Test
    void parsesLegacyOutputNestedResults() throws Exception {
        String body = "{\"output\":{\"results\":[{\"index\":1,\"relevance_score\":0.7}]}}";

        double[] scores = client.parseScores(body, 2);

        assertArrayEquals(new double[]{0.0d, 0.7d}, scores, 1e-9);
    }

    @Test
    void rejectsResponseWithoutResults() {
        assertThrows(Exception.class, () -> client.parseScores("{\"data\":[]}", 2));
        assertThrows(Exception.class, () -> client.parseScores(
                "{\"results\":[{\"index\":99,\"relevance_score\":0.5}]}", 2));
    }

    @Test
    void buildParametersOmitsUnconfiguredOptionalFields() {
        Map<String, Object> parameters = client.buildParameters(resolvedConfig(null, null, null));

        assertEquals(Boolean.FALSE, parameters.get("return_documents"));
        assertFalse(parameters.containsKey("top_n"));
        assertFalse(parameters.containsKey("instruct"));
        assertFalse(parameters.containsKey("fps"));
    }

    @Test
    void buildParametersSendsInstructAndFpsButNeverTopN() {
        // top_n 语义已改为「重排候选窗口」（由调用方控制送入条数），不再出现在请求参数里
        Map<String, Object> parameters = client.buildParameters(resolvedConfig("Retrieve policy clauses.", 50, 0.5d));

        assertEquals("Retrieve policy clauses.", parameters.get("instruct"));
        assertEquals(0.5d, parameters.get("fps"));
        assertFalse(parameters.containsKey("top_n"));
        assertTrue(parameters.containsKey("return_documents"));
    }

    @Test
    void buildParametersIgnoresBlankInstruct() {
        Map<String, Object> parameters = client.buildParameters(resolvedConfig("   ", 10, null));

        assertFalse(parameters.containsKey("instruct"));
        assertFalse(parameters.containsKey("fps"));
        assertTrue(parameters.containsKey("return_documents"));
    }

    @Test
    void buildRequestBodyUsesDashScopeNativeShape() {
        Map<String, Object> body = client.buildRequestBody("什么是知识库?",
                java.util.List.of("文档一", "文档二"), resolvedConfig(null, 5, null));

        assertEquals("qwen3-vl-rerank", body.get("model"));
        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) body.get("input");
        assertEquals(Map.of("text", "什么是知识库?"), input.get("query"));
        assertEquals(2, ((java.util.List<?>) input.get("documents")).size());
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) body.get("parameters");
        assertEquals(Boolean.FALSE, parameters.get("return_documents"));
        assertFalse(parameters.containsKey("top_n"));
    }

    private static ModelConfigService.ResolvedModelConfig resolvedConfig(String instruct, Integer topN, Double fps) {
        return new ModelConfigService.ResolvedModelConfig(
                1L, AiModelOwnerType.SYSTEM, AiModelType.RERANK, "rerank",
                "https://dashscope.aliyuncs.com", "sk-test", "qwen3-vl-rerank",
                null, null, null, null, null, null, null,
                instruct, topN, fps);
    }
}
