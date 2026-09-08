package com.luky.nexusmind.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luky.nexusmind.client.RerankClient;
import com.luky.nexusmind.entity.SearchResult;
import com.luky.nexusmind.model.AiModelOwnerType;
import com.luky.nexusmind.model.AiModelType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class HybridSearchRerankPipelineTest {

    private static SearchResult hit(String md5) {
        return new SearchResult(md5, 1, "content-" + md5, 1.0d);
    }

    private static ModelConfigService.ResolvedModelConfig rerankConfig() {
        return new ModelConfigService.ResolvedModelConfig(
                1L, AiModelOwnerType.SYSTEM, AiModelType.RERANK, "rerank",
                "https://dashscope.aliyuncs.com", "sk-test", "qwen3-vl-rerank",
                null, null, null, null, null, null, null,
                null, null, null);
    }

    /** 捕获送入的文档并返回预设分数，用于验证窗口与重排行为 */
    private static RerankClient stubClient(List<String> capturedDocs, double[] scores) {
        return new RerankClient(new ObjectMapper(),
                new AiTraceService(false, "", "", "", "test", false)) {
            @Override
            public double[] rerank(String query, List<String> documents,
                                   ModelConfigService.ResolvedModelConfig config, String userId) {
                capturedDocs.addAll(documents);
                return scores;
            }
        };
    }

    @Test
    void applyScoresReordersCandidatesByRelevanceAndWritesScoreBack() {
        List<SearchResult> candidates = List.of(hit("a"), hit("b"), hit("c"));

        List<SearchResult> ranked = HybridSearchService.applyScores(candidates, new double[]{0.1d, 0.9d, 0.5d});

        assertEquals(List.of("b", "c", "a"), ranked.stream().map(SearchResult::getFileMd5).toList());
        assertEquals(0.9d, ranked.get(0).getScore());
        assertEquals(0.1d, ranked.get(2).getScore());
    }

    @Test
    void applyScoresKeepsOriginalOrderWhenScoresMissing() {
        List<SearchResult> candidates = List.of(hit("a"), hit("b"));

        assertSame(candidates, HybridSearchService.applyScores(candidates, new double[]{0.4d}));
        assertSame(candidates, HybridSearchService.applyScores(candidates, null));
    }

    @Test
    void applyRerankSkippedWhenPlanMissingOrTooFewCandidates() {
        HybridSearchService service = new HybridSearchService();

        List<SearchResult> candidates = List.of(hit("a"), hit("b"));
        assertSame(candidates, service.applyRerank("query", candidates, null));

        HybridSearchService.RerankPlan plan = new HybridSearchService.RerankPlan(rerankConfig(), 30, "1");
        List<SearchResult> single = List.of(hit("a"));
        assertSame(single, service.applyRerank("query", single, plan));
    }

    @Test
    void applyRerankUsesConfiguredWindowAndReorders() {
        List<String> capturedDocs = new java.util.ArrayList<>();
        HybridSearchService service = new HybridSearchService();
        ReflectionTestUtils.setField(service, "rerankClient", stubClient(capturedDocs, new double[]{0.1d, 0.9d}));

        HybridSearchService.RerankPlan plan = new HybridSearchService.RerankPlan(rerankConfig(), 2, "1");
        List<SearchResult> ranked = service.applyRerank("query", List.of(hit("a"), hit("b")), plan);

        assertEquals(2, capturedDocs.size());
        assertEquals(List.of("b", "a"), ranked.stream().map(SearchResult::getFileMd5).toList());
        assertEquals(0.9d, ranked.get(0).getScore());
    }

    @Test
    void applyRerankKeepsOutOfWindowCandidatesAfterRankedWindow() {
        List<String> capturedDocs = new java.util.ArrayList<>();
        HybridSearchService service = new HybridSearchService();
        ReflectionTestUtils.setField(service, "rerankClient", stubClient(capturedDocs, new double[]{0.8d, 0.2d}));

        // 候选 3 条、窗口 2：前 2 条重排，第 3 条保留融合序垫底，总量不丢失
        HybridSearchService.RerankPlan plan = new HybridSearchService.RerankPlan(rerankConfig(), 2, "1");
        List<SearchResult> ranked = service.applyRerank("query", List.of(hit("a"), hit("b"), hit("c")), plan);

        assertEquals(2, capturedDocs.size());
        assertEquals(3, ranked.size());
        assertEquals(List.of("a", "b", "c"), ranked.stream().map(SearchResult::getFileMd5).toList());
    }

    @Test
    void applyRerankDegradesToFusionOrderWhenClientFails() {
        List<String> capturedDocs = new java.util.ArrayList<>();
        HybridSearchService service = new HybridSearchService();
        // 客户端返回 null（未配置/调用失败）应降级为原序
        ReflectionTestUtils.setField(service, "rerankClient", stubClient(capturedDocs, null));

        HybridSearchService.RerankPlan plan = new HybridSearchService.RerankPlan(rerankConfig(), 30, "1");
        List<SearchResult> candidates = List.of(hit("a"), hit("b"));

        assertSame(candidates, service.applyRerank("query", candidates, plan));
        assertEquals(2, capturedDocs.size());
    }
}
