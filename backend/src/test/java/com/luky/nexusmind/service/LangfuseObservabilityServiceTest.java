package com.luky.nexusmind.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangfuseObservabilityServiceTest {

    private static final Instant FROM = Instant.parse("2026-06-24T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-24T01:00:00Z");

    @Test
    void disabledWhenCredentialsAreMissing() {
        LangfuseObservabilityService service = new LangfuseObservabilityService(
                false,
                "https://cloud.langfuse.com",
                "",
                "",
                new CapturingClient());

        LangfuseObservabilityService.OverviewResponse overview = service.getOverview("alice", FROM, TO);

        assertFalse(overview.enabled());
        assertEquals("Langfuse 未配置或未启用", overview.message());
        assertEquals(0, overview.totalTraces());
        assertEquals(0, overview.totalObservations());
    }

    @Test
    void overviewFiltersByCurrentUserAndAggregatesObservations() {
        CapturingClient client = new CapturingClient();
        client.response = new LangfuseObservabilityService.LangfuseObservationPage(
                List.of(
                        observation("trace-a", "rag.chat", "SPAN", "DEFAULT", "deepseek-chat", 1200, 30, 0.03),
                        observation("trace-a", "llm.deepseek.stream", "GENERATION", "DEFAULT", "deepseek-chat", 800, 70, 0.05),
                        observation("trace-b", "rag.chat", "SPAN", "ERROR", "deepseek-chat", 400, 0, 0.0)
                ),
                null);

        LangfuseObservabilityService service = new LangfuseObservabilityService(
                true,
                "https://cloud.langfuse.com",
                "pk-test",
                "sk-test",
                client);

        LangfuseObservabilityService.OverviewResponse overview = service.getOverview("alice", FROM, TO);

        assertTrue(overview.enabled());
        assertEquals("alice", client.lastUserId);
        assertEquals("core,basic,model,usage,metrics,trace_context", client.lastFields);
        assertEquals(2, overview.totalTraces());
        assertEquals(3, overview.totalObservations());
        assertEquals(1, overview.errorCount());
        assertEquals(800, overview.avgLatencyMs());
        assertEquals(100, overview.totalTokens());
        assertEquals(0.08, overview.totalCost(), 0.0001);
        assertEquals(1, overview.byModel().size());
        assertEquals("deepseek-chat", overview.byModel().get(0).model());
        assertEquals(3, overview.byModel().get(0).count());
    }

    @Test
    void detailDoesNotExposeInputOrOutput() {
        CapturingClient client = new CapturingClient();
        client.response = new LangfuseObservabilityService.LangfuseObservationPage(
                List.of(observation("trace-a", "llm.deepseek.stream", "GENERATION", "DEFAULT", "deepseek-chat", 900, 10, 0.01)),
                null);

        LangfuseObservabilityService service = new LangfuseObservabilityService(
                true,
                "https://cloud.langfuse.com",
                "pk-test",
                "sk-test",
                client);

        LangfuseObservabilityService.TraceDetailResponse detail = service.getTraceDetail("alice", "trace-a", FROM, TO);

        assertEquals("trace-a", client.lastTraceId);
        assertEquals(1, detail.observations().size());
        LangfuseObservabilityService.ObservationView row = detail.observations().get(0);
        assertNull(row.input());
        assertNull(row.output());
        assertEquals("deepseek-chat", row.modelName());
    }

    @Test
    void detailFallsBackToMetadataWhenLangfuseSummaryFieldsAreMissing() {
        CapturingClient client = new CapturingClient();
        client.response = new LangfuseObservabilityService.LangfuseObservationPage(
                List.of(new LangfuseObservabilityService.LangfuseObservation(
                        "generation-id",
                        "trace-a",
                        Instant.parse("2026-06-24T00:00:00Z"),
                        Instant.parse("2026-06-24T00:00:01Z"),
                        null,
                        "GENERATION",
                        "llm.deepseek.stream",
                        "DEFAULT",
                        "",
                        "prod",
                        "session-1",
                        null,
                        null,
                        null,
                        0.9,
                        "nexusmind-rag-chat",
                        Map.of("attributes", Map.of(
                                "langfuse.observation.model.name", "deepseek-chat",
                                "gen_ai.usage.prompt_tokens", 12,
                                "gen_ai.usage.completion_tokens", 8,
                                "gen_ai.usage.total_tokens", 20,
                                "gen_ai.usage.cost", 0.00002
                        )),
                        null,
                        null)),
                null);

        LangfuseObservabilityService service = new LangfuseObservabilityService(
                true,
                "https://cloud.langfuse.com",
                "pk-test",
                "sk-test",
                client);

        LangfuseObservabilityService.TraceDetailResponse detail = service.getTraceDetail("alice", "trace-a", FROM, TO);

        LangfuseObservabilityService.ObservationView row = detail.observations().get(0);
        assertEquals("deepseek-chat", row.modelName());
        assertEquals(20, row.totalTokens());
        assertEquals(0.00002, row.totalCost(), 0.000001);
    }

    private static LangfuseObservabilityService.LangfuseObservation observation(String traceId,
                                                                               String name,
                                                                               String type,
                                                                               String level,
                                                                               String model,
                                                                               long latencyMs,
                                                                               long tokens,
                                                                               double cost) {
        return new LangfuseObservabilityService.LangfuseObservation(
                name + "-id",
                traceId,
                Instant.parse("2026-06-24T00:00:00Z"),
                Instant.parse("2026-06-24T00:00:01Z"),
                null,
                type,
                name,
                level,
                "",
                "prod",
                "session-1",
                model,
                tokens,
                cost,
                latencyMs / 1000.0,
                "nexusmind-rag-chat",
                Map.of("nexusmind.context.length", 120),
                null,
                null);
    }

    private static class CapturingClient implements LangfuseObservabilityService.LangfuseObservationClient {
        private LangfuseObservabilityService.LangfuseObservationPage response =
                new LangfuseObservabilityService.LangfuseObservationPage(List.of(), null);
        private String lastUserId;
        private String lastTraceId;
        private String lastFields;

        @Override
        public LangfuseObservabilityService.LangfuseObservationPage fetchObservations(
                LangfuseObservabilityService.LangfuseObservationQuery query) {
            this.lastUserId = query.userId();
            this.lastTraceId = query.traceId();
            this.lastFields = query.fields();
            return response;
        }
    }
}
