package com.luky.nexusmind.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luky.nexusmind.client.EmbeddingClient;
import com.luky.nexusmind.client.RerankClient;
import com.luky.nexusmind.entity.EsDocument;
import com.luky.nexusmind.entity.SearchResult;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.repository.UserRepository;
import com.luky.nexusmind.repository.FileUploadRepository;
import com.luky.nexusmind.model.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.FieldValue;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 混合搜索服务，结合文本匹配和向量相似度搜索
 * 支持权限过滤，确保用户只能搜索其有权限访问的文档
 */
@Service
public class HybridSearchService {

    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private RerankClient rerankClient;

    @Autowired
    private ModelConfigService modelConfigService;

    @Autowired
    private AiTraceService aiTraceService;

    @Value("${ai.retrieval.fusion-mode:rrf}")
    private String fusionMode;

    @Value("${ai.retrieval.rrf-k:60}")
    private int rrfK;

    @Value("${ai.retrieval.rank-window:60}")
    private int rankWindow;

    @Value("${ai.retrieval.rerank-enabled:true}")
    private boolean rerankEnabled;

    @Value("${ai.retrieval.rerank-top-n:30}")
    private int rerankTopN;

    /** RRF 双路召回并行执行器（daemon 线程，不阻塞 JVM 退出） */
    private static final ExecutorService SEARCH_BRANCH_EXECUTOR = Executors.newWorkStealingPool();

    /**
     * 使用文本匹配和向量相似度进行混合搜索，支持权限过滤
     * 该方法确保用户只能搜索其有权限访问的文档（自己的文档、公开文档、所属组织的文档）
     *
     * @param query  查询字符串
     * @param userId 用户ID
     * @param topK   返回结果数量
     * @return 搜索结果列表
     */
    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        return searchWithPermission(query, userId, topK, null);
    }

    public List<SearchResult> searchWithPermission(String query, String userId, int topK,
                                                   Set<String> scopeFileMd5s) {
        logger.debug("开始带权限搜索，查询: {}, 用户ID: {}", query, userId);

        if (scopeFileMd5s != null && scopeFileMd5s.isEmpty()) return List.of();

        AiTraceService.TraceSpan span = aiTraceService.startSpan("rag.hybrid_search", userId, null, null)
                .attribute("nexusmind.search.top_k", topK)
                .attribute("nexusmind.search.query.length", query != null ? query.length() : 0);
        if (aiTraceService.shouldCaptureContent()) {
            span.attribute("input.value", abbreviate(query, 2000))
                    .attribute("langfuse.observation.input", abbreviate(query, 2000));
        }
        try {
            // 获取用户有效的组织标签（包含层级关系）
            List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
            logger.debug("用户 {} 的有效组织标签: {}", userId, userEffectiveTags);

            // 获取用户的数据库ID用于权限过滤
            String userDbId = getUserDbId(userId);
            boolean administrator = isAdministrator(userId);
            logger.debug("用户 {} 的数据库ID: {}", userId, userDbId);

            List<SearchResult> results;
            if ("legacy".equalsIgnoreCase(fusionMode)) {
                span.attribute("nexusmind.search.fusion_mode", "legacy");
                results = legacyHybridSearch(query, userId, userDbId, userEffectiveTags, administrator, topK,
                        scopeFileMd5s, span);
                attachSearchOutputContent(span, results);
            } else {
                results = rrfHybridSearch(query, userId, userEffectiveTags, administrator, topK, scopeFileMd5s, span);
            }
            span.attribute("nexusmind.search.results.count", results.size());
            return results;
        } catch (Exception e) {
            span.error(e);
            logger.error("带权限的搜索失败", e);
            // 发生异常时尝试使用纯文本搜索作为后备方案
            try {
                logger.info("尝试使用纯文本搜索作为后备方案");
                List<SearchResult> results = textOnlySearchWithPermission(query, getUserDbId(userId),
                        getUserEffectiveOrgTags(userId), isAdministrator(userId), topK, scopeFileMd5s, span);
                span.attribute("nexusmind.search.degraded_to", "text_only");
                span.attribute("nexusmind.search.results.count", results.size());
                return results;
            } catch (Exception fallbackError) {
                logger.error("后备搜索也失败", fallbackError);
                throw new IllegalStateException("知识库检索及纯文本降级均失败", fallbackError);
            }
        } finally {
            span.end();
            span.close();
        }
    }

    /**
     * 旧版单请求融合（kNN ∪ BM25 + BM25 rescore），通过 ai.retrieval.fusion-mode=legacy 回退使用
     */
    private List<SearchResult> legacyHybridSearch(String query, String userId, String userDbId,
                                                  List<String> userEffectiveTags, boolean administrator,
                                                  int topK, Set<String> scopeFileMd5s,
                                                  AiTraceService.TraceSpan span) throws IOException {
        // 生成查询向量
        final List<Float> queryVector = embedToVectorList(query, userId);

        if (queryVector == null) {
            logger.warn("向量生成失败，仅使用文本匹配进行搜索");
            span.attribute("nexusmind.search.vector_failed", true);
            return textOnlySearchWithPermission(
                    query, userDbId, userEffectiveTags, administrator, topK, scopeFileMd5s, span);
        }

        List<Query> retrievalFilters = new ArrayList<>();
        retrievalFilters.add(buildPermissionQuery(userDbId, userEffectiveTags, administrator));
        if (scopeFileMd5s != null) retrievalFilters.add(buildFileScopeQuery(scopeFileMd5s));

        SearchResponse<EsDocument> response = esClient.search(s -> {
            s.index("knowledge_base");
            // KNN 召回
            int recallK = topK * 30; // KNN 召回窗口
            s.knn(kn -> kn
                    .field("vector")
                    .queryVector(queryVector)
                    .k(recallK)
                    .numCandidates(recallK)
                    .filter(retrievalFilters));
            // 必须命中关键词 + 权限过滤
            s.query(q -> q.bool(b -> {
                b.must(mst -> mst.match(m -> m.field("textContent").query(query)))
                        .filter(buildPermissionQuery(userDbId, userEffectiveTags, administrator));
                if (scopeFileMd5s != null) b.filter(buildFileScopeQuery(scopeFileMd5s));
                return b;
            }));

            // 第二阶段 BM25 rescore
            s.rescore(r -> r
                    .windowSize(recallK)
                    .query(rq -> rq
                            .queryWeight(0.2d) // 保留部分 KNN 分
                            .rescoreQueryWeight(1.0d) // BM25 主导
                            .query(rqq -> rqq.match(m -> m
                                    .field("textContent")
                                    .query(query)
                                    .operator(Operator.And)))));
            s.size(topK);
            return s;
        }, EsDocument.class);

        logger.debug("Elasticsearch查询执行完成，命中数量: {}, 最大分数: {}",
                response.hits().total().value(), response.hits().maxScore());

        List<SearchResult> results = toSearchResults(response);
        logger.debug("返回搜索结果数量: {}", results.size());
        results = attachFileNames(results);
        return results;
    }

    /**
     * RRF 管线：kNN 与 BM25 两路并行召回 -> Reciprocal Rank Fusion -> Rerank 重排 -> 截断 topK。
     * 降级链：向量失败走纯文本；任一路召回失败用另一路；rerank 未配置/失败保持融合原序。
     */
    private List<SearchResult> rrfHybridSearch(String query, String userId, List<String> userEffectiveTags,
                                               boolean administrator, int topK, Set<String> scopeFileMd5s,
                                               AiTraceService.TraceSpan span) {
        // 生成查询向量
        final List<Float> queryVector = embedToVectorList(query, userId);

        // 如果向量生成失败，仅使用文本匹配
        if (queryVector == null) {
            logger.warn("向量生成失败，仅使用文本匹配进行搜索");
            span.attribute("nexusmind.search.vector_failed", true);
            return textOnlySearchWithPermission(query, getUserDbId(userId), userEffectiveTags, administrator,
                    topK, scopeFileMd5s, span);
        }

        String userDbId = getUserDbId(userId);
        Query permission = buildPermissionQuery(userDbId, userEffectiveTags, administrator);
        Query scopeFilter = scopeFileMd5s != null ? buildFileScopeQuery(scopeFileMd5s) : null;

        CompletableFuture<RecallResult> knnFuture = CompletableFuture.supplyAsync(
                () -> knnRecall(queryVector, permission, scopeFilter), SEARCH_BRANCH_EXECUTOR);
        CompletableFuture<RecallResult> bm25Future = CompletableFuture.supplyAsync(
                () -> bm25Recall(query, permission, scopeFilter), SEARCH_BRANCH_EXECUTOR);

        RecallResult knnResult = joinBranch("kNN", knnFuture);
        RecallResult bm25Result = joinBranch("BM25", bm25Future);
        if (!knnResult.succeeded() && !bm25Result.succeeded()) {
            throw new IllegalStateException("kNN 与 BM25 召回均失败");
        }
        List<SearchResult> knnHits = knnResult.hits();
        List<SearchResult> bm25Hits = bm25Result.hits();
        span.attribute("nexusmind.search.knn_hits", knnHits.size())
                .attribute("nexusmind.search.bm25_hits", bm25Hits.size());
        if (knnHits.isEmpty() && bm25Hits.isEmpty()) {
            return List.of();
        }

        RerankPlan plan = resolveRerankPlan(userId);
        List<SearchResult> fused = RrfFuser.fuse(knnHits, bm25Hits, rrfK,
                Math.max(topK, plan != null ? plan.window() : 0));
        span.attribute("nexusmind.search.fused_count", fused.size())
                .attribute("nexusmind.search.rrf_k", rrfK);
        logger.debug("RRF 融合完成: kNN {} 条 + BM25 {} 条 -> {} 条", knnHits.size(), bm25Hits.size(), fused.size());

        List<SearchResult> ranked = applyRerank(query, fused, plan, span);
        List<SearchResult> results = ranked.size() > topK ? new ArrayList<>(ranked.subList(0, topK)) : ranked;
        results = attachFileNames(results);
        attachFunnelOutput(span, knnHits, bm25Hits, fused, ranked, topK);
        return results;
    }

    private List<SearchResult> textOnlySearchWithPermission(String query, String userDbId,
            List<String> userEffectiveTags, boolean administrator, int topK, Set<String> scopeFileMd5s,
            AiTraceService.TraceSpan span) {
        try {
            logger.debug("开始执行纯文本搜索，用户数据库ID: {}, 标签: {}", userDbId, userEffectiveTags);

            RerankPlan plan = resolveRerankPlan(userDbId);
            SearchResponse<EsDocument> response = esClient.search(s -> s
                    .index("knowledge_base")
                    .query(q -> q.bool(b -> {
                        b.must(m -> m.match(ma -> ma.field("textContent").query(query)))
                                .filter(buildPermissionQuery(userDbId, userEffectiveTags, administrator));
                        if (scopeFileMd5s != null) b.filter(buildFileScopeQuery(scopeFileMd5s));
                        return b;
                    }))
                    .minScore(0.3d)
                    .size(Math.max(topK, plan != null ? plan.window() : rerankTopN)),
                    EsDocument.class);

            logger.debug("纯文本查询执行完成，命中数量: {}, 最大分数: {}",
                    response.hits().total().value(), response.hits().maxScore());

            List<SearchResult> results = toSearchResults(response);
            List<SearchResult> ranked = applyRerank(query, results, plan, span);
            List<SearchResult> limited = ranked.size() > topK ? new ArrayList<>(ranked.subList(0, topK)) : ranked;

            logger.debug("返回纯文本搜索结果数量: {}", limited.size());
            limited = attachFileNames(limited);
            attachFunnelOutput(span, null, results, null, ranked, topK);
            return limited;
        } catch (Exception e) {
            logger.error("纯文本搜索失败", e);
            throw new IllegalStateException("纯文本搜索失败", e);
        }
    }

    /**
     * 匿名搜索方法，仅返回公开且不属于私人空间的文档。
     */
    public List<SearchResult> search(String query, int topK) {
        AiTraceService.TraceSpan span = aiTraceService.startSpan("rag.hybrid_search", null, null, null)
                .attribute("nexusmind.search.top_k", topK)
                .attribute("nexusmind.search.query.length", query != null ? query.length() : 0)
                .attribute("nexusmind.search.anonymous", true);
        if (aiTraceService.shouldCaptureContent()) {
            span.attribute("input.value", abbreviate(query, 2000))
                    .attribute("langfuse.observation.input", abbreviate(query, 2000));
        }
        try {
            logger.debug("开始混合检索，查询: {}, topK: {}", query, topK);

            List<SearchResult> results;
            if ("legacy".equalsIgnoreCase(fusionMode)) {
                span.attribute("nexusmind.search.fusion_mode", "legacy");
                results = legacyAnonymousSearch(query, topK, span);
                attachSearchOutputContent(span, results);
            } else {
                results = rrfAnonymousSearch(query, topK, span);
            }
            span.attribute("nexusmind.search.results.count", results.size());
            return results;
        } catch (Exception e) {
            span.error(e);
            logger.error("搜索失败", e);
            // 发生异常时尝试使用纯文本搜索作为后备方案
            try {
                logger.info("尝试使用纯文本搜索作为后备方案");
                List<SearchResult> results = textOnlySearch(query, topK, span);
                span.attribute("nexusmind.search.degraded_to", "text_only");
                span.attribute("nexusmind.search.results.count", results.size());
                return results;
            } catch (Exception fallbackError) {
                logger.error("后备搜索也失败", fallbackError);
                throw new IllegalStateException("知识库检索及纯文本降级均失败", fallbackError);
            }
        } finally {
            span.end();
            span.close();
        }
    }

    /**
     * 旧版匿名混合检索（单请求融合），通过 ai.retrieval.fusion-mode=legacy 回退使用
     */
    private List<SearchResult> legacyAnonymousSearch(String query, int topK, AiTraceService.TraceSpan span) throws Exception {
        // 生成查询向量
        final List<Float> queryVector = embedToVectorList(query, null);

        // 如果向量生成失败，仅使用文本匹配
        if (queryVector == null) {
            logger.warn("向量生成失败，仅使用文本匹配进行搜索");
            span.attribute("nexusmind.search.vector_failed", true);
            return textOnlySearch(query, topK, span);
        }

        SearchResponse<EsDocument> response = esClient.search(s -> {
            s.index("knowledge_base");
            int recallK = topK * 30;
            s.knn(kn -> kn
                    .field("vector")
                    .queryVector(queryVector)
                    .k(recallK)
                    .numCandidates(recallK)
                    .filter(buildPublicPermissionQuery()));

            s.query(q -> q.bool(b -> b
                    .must(m -> m.match(match -> match.field("textContent").query(query)))
                    .filter(buildPublicPermissionQuery())));

            // rescore BM25
            s.rescore(r -> r
                    .windowSize(recallK)
                    .query(rq -> rq
                            .queryWeight(0.2d)
                            .rescoreQueryWeight(1.0d)
                            .query(rqq -> rqq.match(m -> m
                                    .field("textContent")
                                    .query(query)
                                    .operator(Operator.And)))));
            s.size(topK);
            return s;
        }, EsDocument.class);

        return attachFileNames(toSearchResultsBasic(response));
    }

    /**
     * RRF 匿名管线：公开范围内 kNN/BM25 双路并行召回 -> RRF -> Rerank -> 截断 topK
     */
    private List<SearchResult> rrfAnonymousSearch(String query, int topK, AiTraceService.TraceSpan span) {
        final List<Float> queryVector = embedToVectorList(query, null);
        if (queryVector == null) {
            logger.warn("向量生成失败，仅使用文本匹配进行搜索");
            span.attribute("nexusmind.search.vector_failed", true);
            try {
                return textOnlySearch(query, topK, span);
            } catch (Exception e) {
                logger.error("纯文本后备搜索失败", e);
                throw new IllegalStateException("纯文本后备搜索失败", e);
            }
        }

        Query permission = buildPublicPermissionQuery();
        CompletableFuture<RecallResult> knnFuture = CompletableFuture.supplyAsync(
                () -> knnRecall(queryVector, permission, null), SEARCH_BRANCH_EXECUTOR);
        CompletableFuture<RecallResult> bm25Future = CompletableFuture.supplyAsync(
                () -> bm25Recall(query, permission, null), SEARCH_BRANCH_EXECUTOR);

        RecallResult knnResult = joinBranch("kNN", knnFuture);
        RecallResult bm25Result = joinBranch("BM25", bm25Future);
        if (!knnResult.succeeded() && !bm25Result.succeeded()) {
            throw new IllegalStateException("kNN 与 BM25 召回均失败");
        }
        List<SearchResult> knnHits = knnResult.hits();
        List<SearchResult> bm25Hits = bm25Result.hits();
        span.attribute("nexusmind.search.knn_hits", knnHits.size())
                .attribute("nexusmind.search.bm25_hits", bm25Hits.size());
        if (knnHits.isEmpty() && bm25Hits.isEmpty()) {
            return List.of();
        }

        RerankPlan plan = resolveRerankPlan(null);
        List<SearchResult> fused = RrfFuser.fuse(knnHits, bm25Hits, rrfK,
                Math.max(topK, plan != null ? plan.window() : 0));
        span.attribute("nexusmind.search.fused_count", fused.size())
                .attribute("nexusmind.search.rrf_k", rrfK);
        List<SearchResult> ranked = applyRerank(query, fused, plan, span);
        attachFunnelOutput(span, knnHits, bm25Hits, fused, ranked, topK);
        return attachFileNames(ranked.size() > topK ? new ArrayList<>(ranked.subList(0, topK)) : ranked);
    }

    /**
     * 仅使用文本匹配的搜索方法（匿名），召回窗口扩大后经 rerank 重排再截断
     */
    private List<SearchResult> textOnlySearch(String query, int topK, AiTraceService.TraceSpan span) throws Exception {
        RerankPlan plan = resolveRerankPlan(null);
        SearchResponse<EsDocument> response = esClient.search(s -> s
                .index("knowledge_base")
                .query(q -> q.bool(b -> b
                        .must(m -> m.match(match -> match.field("textContent").query(query)))
                        .filter(buildPublicPermissionQuery())))
                .size(Math.max(topK, plan != null ? plan.window() : rerankTopN)),
                EsDocument.class);

        List<SearchResult> results = toSearchResultsBasic(response);
        List<SearchResult> ranked = applyRerank(query, results, plan, span);
        attachFunnelOutput(span, null, results, null, ranked, topK);
        return attachFileNames(ranked.size() > topK ? new ArrayList<>(ranked.subList(0, topK)) : ranked);
    }

    private record RecallResult(boolean succeeded, List<SearchResult> hits) {
        static RecallResult success(List<SearchResult> hits) {
            return new RecallResult(true, hits);
        }

        static RecallResult failure() {
            return new RecallResult(false, List.of());
        }
    }

    /** kNN 召回分支：失败返回失败状态，由另一路继续（不中断整条链路） */
    private RecallResult knnRecall(List<Float> queryVector, Query permission, Query scopeFilter) {
        try {
            SearchResponse<EsDocument> response = esClient.search(s -> {
                s.index("knowledge_base");
                s.knn(kn -> kn
                        .field("vector")
                        .queryVector(queryVector)
                        .k(rankWindow)
                        .numCandidates(rankWindow * 4)
                        .filter(scopeFilter == null ? List.of(permission) : List.of(permission, scopeFilter)));
                s.size(rankWindow);
                return s;
            }, EsDocument.class);
            return RecallResult.success(toSearchResults(response));
        } catch (Exception e) {
            logger.warn("kNN 召回分支失败，该路结果置空: {}", e.getMessage());
            return RecallResult.failure();
        }
    }

    /** BM25 召回分支：失败返回失败状态，由另一路继续（不中断整条链路） */
    private RecallResult bm25Recall(String query, Query permission, Query scopeFilter) {
        try {
            SearchResponse<EsDocument> response = esClient.search(s -> s
                    .index("knowledge_base")
                    .query(q -> q.bool(b -> {
                        b.must(mst -> mst.match(m -> m.field("textContent").query(query)));
                        b.filter(permission);
                        if (scopeFilter != null) b.filter(scopeFilter);
                        return b;
                    }))
                    .size(rankWindow),
                    EsDocument.class);
            return RecallResult.success(toSearchResults(response));
        } catch (Exception e) {
            logger.warn("BM25 召回分支失败，该路结果置空: {}", e.getMessage());
            return RecallResult.failure();
        }
    }

    private RecallResult joinBranch(String branch, CompletableFuture<RecallResult> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warn("{} 召回分支执行失败，使用另一路继续: {}", branch, e.getMessage());
            return RecallResult.failure();
        }
    }

    /**
     * 一次搜索的 rerank 执行计划：解析到的模型配置 + 生效窗口 + 追踪用户。
     * 窗口优先级：模型配置的 top_n（重排候选条数）> 全局 ai.retrieval.rerank-top-n。
     */
    record RerankPlan(ModelConfigService.ResolvedModelConfig config, int window, String userId) {
    }

    private RerankPlan resolveRerankPlan(String userIdOrName) {
        if (!rerankEnabled) {
            return null;
        }
        ModelConfigService.ResolvedModelConfig config = modelConfigService.resolveRerankConfig(userIdOrName).orElse(null);
        if (config == null) {
            return null;
        }
        int window = config.topN() != null && config.topN() > 0 ? config.topN() : rerankTopN;
        return new RerankPlan(config, window, userIdOrName);
    }

    /**
     * 调用 rerank 重排候选；无计划/候选不足/调用失败时原样返回（保持 RRF 融合序）。
     * 窗口内的候选按相关性重排；窗口外的候选保留 RRF 融合序排在其后，保证结果总量不丢失。
     */
    List<SearchResult> applyRerank(String query, List<SearchResult> candidates, RerankPlan plan) {
        return applyRerank(query, candidates, plan, null);
    }

    List<SearchResult> applyRerank(String query, List<SearchResult> candidates, RerankPlan plan,
                                   AiTraceService.TraceSpan span) {
        if (plan == null || candidates == null || candidates.size() < 2) {
            if (span != null) span.attribute("nexusmind.search.rerank_applied", false);
            return candidates;
        }
        try {
            List<SearchResult> window = candidates.size() > plan.window()
                    ? candidates.subList(0, plan.window())
                    : candidates;
            if (span != null) {
                span.attribute("nexusmind.search.rerank_window", plan.window())
                        .attribute("nexusmind.search.rerank_docs_sent", window.size());
            }
            List<String> documents = window.stream().map(SearchResult::getTextContent).toList();
            double[] scores = rerankClient.rerank(query, documents, plan.config(), plan.userId());
            if (scores == null) {
                if (span != null) span.attribute("nexusmind.search.rerank_applied", false);
                return candidates;
            }
            List<SearchResult> ranked = applyScores(window, scores);
            if (span != null) span.attribute("nexusmind.search.rerank_applied", true);
            if (window.size() < candidates.size()) {
                List<SearchResult> merged = new ArrayList<>(ranked);
                merged.addAll(candidates.subList(window.size(), candidates.size()));
                return merged;
            }
            return ranked;
        } catch (Exception e) {
            logger.warn("应用 rerank 结果失败，保持融合排序: {}", e.getMessage());
            if (span != null) span.attribute("nexusmind.search.rerank_applied", false);
            return candidates;
        }
    }

    /** 按相关性分数降序重排候选，并把分数写回 SearchResult.score */
    static List<SearchResult> applyScores(List<SearchResult> candidates, double[] scores) {
        if (candidates == null || scores == null || scores.length < candidates.size()) {
            return candidates;
        }
        Integer[] order = new Integer[candidates.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Double.compare(scores[b], scores[a]));
        List<SearchResult> ranked = new ArrayList<>(order.length);
        for (int index : order) {
            SearchResult hit = candidates.get(index);
            ranked.add(new SearchResult(
                    hit.getFileMd5(),
                    hit.getChunkId(),
                    hit.getTextContent(),
                    scores[index],
                    hit.getUserId(),
                    hit.getOrgTag(),
                    Boolean.TRUE.equals(hit.getIsPublic()),
                    hit.getFileName()));
        }
        return ranked;
    }

    /** 检索漏斗构建器（仅内容采集开启时使用） */
    private static final ObjectMapper FUNNEL_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * 把检索漏斗各阶段的命中分片写入 span 输出：kNN/BM25 各自召回了哪些分片、RRF 融合序、重排后最终序。
     */
    private void attachFunnelOutput(AiTraceService.TraceSpan span, List<SearchResult> knn, List<SearchResult> bm25,
                                    List<SearchResult> fused, List<SearchResult> finalResults, int finalLimit) {
        if (!aiTraceService.shouldCaptureContent()) {
            return;
        }
        try {
            ObjectNode funnel = FUNNEL_MAPPER.createObjectNode();
            ObjectNode totals = funnel.putObject("totals");
            totals.put("knn_hits", knn == null ? 0 : knn.size());
            totals.put("bm25_hits", bm25 == null ? 0 : bm25.size());
            totals.put("fused", fused == null ? 0 : fused.size());
            if (finalResults != null) totals.put("final", finalResults.size());
            if (knn != null) funnel.set("knn_recall", funnelArray(knn, 20));
            if (bm25 != null) funnel.set("bm25_recall", funnelArray(bm25, 20));
            if (fused != null) funnel.set("rrf_fused", funnelArray(fused, 30));
            if (finalResults != null) funnel.set("final", funnelArray(finalResults, finalLimit));
            String json = FUNNEL_MAPPER.writeValueAsString(funnel);
            span.attribute("langfuse.observation.output", json)
                    .attribute("output.value", json);
        } catch (Exception e) {
            logger.warn("构建检索漏斗输出失败: {}", e.getMessage());
        }
    }

    private ArrayNode funnelArray(List<SearchResult> hits, int limit) {
        ArrayNode array = FUNNEL_MAPPER.createArrayNode();
        int size = Math.min(hits.size(), limit);
        for (int i = 0; i < size; i++) {
            SearchResult hit = hits.get(i);
            ObjectNode node = array.addObject();
            node.put("rank", i + 1);
            node.put("file", hit.getFileName() != null ? hit.getFileName() : hit.getFileMd5());
            node.put("chunk", hit.getChunkId());
            if (hit.getScore() != null) node.put("score", hit.getScore());
            String text = hit.getTextContent();
            if (text != null && text.length() > 80) text = text.substring(0, 80) + "…";
            node.put("preview", text);
        }
        return array;
    }

    private void attachSearchOutputContent(AiTraceService.TraceSpan span, List<SearchResult> results) {
        if (!aiTraceService.shouldCaptureContent() || results == null || results.isEmpty()) {
            return;
        }
        StringBuilder output = new StringBuilder();
        int limit = Math.min(3, results.size());
        for (int i = 0; i < limit; i++) {
            SearchResult hit = results.get(i);
            output.append("[").append(i + 1).append("] ")
                    .append(hit.getFileName() != null ? hit.getFileName() : hit.getFileMd5())
                    .append("#").append(hit.getChunkId())
                    .append(" score=").append(hit.getScore())
                    .append(": ").append(abbreviate(hit.getTextContent(), 120)).append('\n');
        }
        span.attribute("output.value", abbreviate(output.toString(), 2000))
                .attribute("langfuse.observation.output", abbreviate(output.toString(), 2000));
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "…";
    }

    /** 带权限场景的结果映射（保留归属字段） */
    private List<SearchResult> toSearchResults(SearchResponse<EsDocument> response) {
        return response.hits().hits().stream()
                .map(hit -> {
                    assert hit.source() != null;
                    return new SearchResult(
                            hit.source().getFileMd5(),
                            hit.source().getChunkId(),
                            hit.source().getTextContent(),
                            hit.score(),
                            hit.source().getUserId(),
                            hit.source().getOrgTag(),
                            hit.source().isPublic());
                })
                .toList();
    }

    /** 匿名场景的结果映射（不向外暴露上传者与组织标签） */
    private List<SearchResult> toSearchResultsBasic(SearchResponse<EsDocument> response) {
        return response.hits().hits().stream()
                .map(hit -> {
                    assert hit.source() != null;
                    return new SearchResult(
                            hit.source().getFileMd5(),
                            hit.source().getChunkId(),
                            hit.source().getTextContent(),
                            hit.score());
                })
                .toList();
    }

    /**
     * 生成查询向量，返回 List<Float>，失败时返回 null
     */
    private List<Float> embedToVectorList(String text, String userId) {
        try {
            List<float[]> vecs = embeddingClient.embed(List.of(text), userId, null);
            if (vecs == null || vecs.isEmpty()) {
                logger.warn("生成的向量为空");
                return null;
            }
            float[] raw = vecs.get(0);
            List<Float> list = new ArrayList<>(raw.length);
            for (float v : raw) {
                list.add(v);
            }
            return list;
        } catch (Exception e) {
            logger.error("生成向量失败", e);
            return null;
        }
    }

    /**
     * 获取用户的有效组织标签（包含层级关系）
     */
    private List<String> getUserEffectiveOrgTags(String userId) {
        logger.debug("获取用户有效组织标签，用户ID: {}", userId);
        try {
            // 获取用户名
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                logger.debug("解析用户ID为Long: {}", userIdLong);
                user = userRepository.findById(userIdLong)
                        .orElseThrow(
                                () -> new CustomException("用户不存在，ID：" + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过ID找到用户: {}", user.getUsername());
            } catch (NumberFormatException e) {
                // 如果userId不是数字格式，则假设它就是username
                logger.debug("用户ID不是数字格式，作为用户名查找: {}", userId);
                user = userRepository.findByUsername(userId)
                        .orElseThrow(() -> new CustomException("用户不存在：" + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过用户名找到用户: {}", user.getUsername());
            }

            // 通过orgTagCacheService获取用户的有效标签集合
            List<String> effectiveTags = orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
            logger.debug("用户 {} 的有效组织标签: {}", user.getUsername(), effectiveTags);
            return effectiveTags;
        } catch (Exception e) {
            logger.error("获取用户有效组织标签失败: {}", e.getMessage(), e);
            return Collections.emptyList(); // 返回空列表作为默认值
        }
    }

    /**
     * 获取用户的数据库ID用于权限过滤
     */
    private String getUserDbId(String userId) {
        logger.debug("获取用户数据库ID，用户ID: {}", userId);
        try {
            // 获取用户名
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                logger.debug("解析用户ID为Long: {}", userIdLong);
                user = userRepository.findById(userIdLong)
                        .orElseThrow(
                                () -> new CustomException("用户不存在，ID：" + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过ID找到用户: {}", user.getUsername());
                return userIdLong.toString(); // 如果输入已经是数字ID，直接返回
            } catch (NumberFormatException e) {
                // 如果userId不是数字格式，则假设它就是username
                logger.debug("用户ID不是数字格式，作为用户名查找: {}", userId);
                user = userRepository.findByUsername(userId)
                        .orElseThrow(() -> new CustomException("用户不存在：" + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过用户名找到用户: {}, ID: {}", user.getUsername(), user.getId());
                return user.getId().toString(); // 返回用户的数据库ID
            }
        } catch (Exception e) {
            logger.error("获取用户数据库ID失败: {}", e.getMessage(), e);
            throw new RuntimeException("获取用户数据库ID失败", e);
        }
    }

    Query buildPermissionQuery(String userDbId, List<String> userEffectiveTags, boolean administrator) {
        if (administrator) {
            return Query.of(q -> q.matchAll(m -> m));
        }

        List<String> sharedTags = userEffectiveTags.stream()
                .filter(tag -> !DocumentPermissionPolicy.isPrivateOrgTag(tag))
                .toList();
        Query privateTag = Query.of(q -> q.prefix(p -> p
                .field("orgTag")
                .value(DocumentPermissionPolicy.PRIVATE_TAG_PREFIX)));

        return Query.of(q -> q.bool(b -> b
                .minimumShouldMatch("1")
                .should(owner -> owner.term(t -> t.field("userId").value(userDbId)))
                .should(publicDocument -> publicDocument.bool(shared -> shared
                        .must(isPublic -> isPublic.term(t -> t.field("public").value(true)))
                        .mustNot(privateTag)))
                .should(organizationDocument -> organizationDocument.bool(shared -> {
                    shared.mustNot(privateTag);
                    if (sharedTags.isEmpty()) {
                        return shared.must(noTags -> noTags.matchNone(m -> m));
                    }
                    return shared.must(tags -> tags.bool(tagOptions -> {
                        sharedTags.forEach(tag -> tagOptions
                                .should(option -> option.term(t -> t.field("orgTag").value(tag))));
                        return tagOptions.minimumShouldMatch("1");
                    }));
                }))));
    }

    Query buildPublicPermissionQuery() {
        return Query.of(q -> q.bool(b -> b
                .must(isPublic -> isPublic.term(t -> t.field("public").value(true)))
                .mustNot(privateTag -> privateTag.prefix(p -> p
                        .field("orgTag")
                        .value(DocumentPermissionPolicy.PRIVATE_TAG_PREFIX)))));
    }

    private Query buildFileScopeQuery(Set<String> fileMd5s) {
        List<FieldValue> values = fileMd5s.stream().map(FieldValue::of).toList();
        return Query.of(q -> q.terms(t -> t.field("fileMd5").terms(terms -> terms.value(values))));
    }

    private boolean isAdministrator(String userId) {
        try {
            User user;
            try {
                user = userRepository.findById(Long.parseLong(userId)).orElse(null);
            } catch (NumberFormatException e) {
                user = userRepository.findByUsername(userId).orElse(null);
            }
            return user != null && user.getRole().isAdministrator();
        } catch (Exception e) {
            logger.warn("无法确定用户角色，按普通用户权限处理: userId={}", userId, e);
            return false;
        }
    }

    private List<SearchResult> attachFileNames(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return results;
        }
        try {
            // 收集所有唯一的 fileMd5
            Set<String> md5Set = results.stream()
                    .map(SearchResult::getFileMd5)
                    .collect(Collectors.toSet());
            List<FileUpload> uploads = fileUploadRepository.findByFileMd5In(new java.util.ArrayList<>(md5Set));
            Map<String, List<FileUpload>> byKey = uploads.stream()
                    .collect(Collectors.groupingBy(FileUpload::getFileMd5));
            // Old content-MD5 keys with multiple owners cannot identify one document safely.
            List<SearchResult> safe = results.stream()
                    .filter(r -> byKey.containsKey(r.getFileMd5()) && byKey.get(r.getFileMd5()).size() == 1
                            && !byKey.get(r.getFileMd5()).get(0).isLegacyShared())
                    .toList();
            safe.forEach(r -> r.setFileName(byKey.get(r.getFileMd5()).get(0).getFileName()));
            return safe;
        } catch (Exception e) {
            logger.error("补充文件名失败", e);
            return List.of();
        }
    }
}
