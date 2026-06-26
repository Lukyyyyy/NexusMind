package com.luky.nexusmind.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luky.nexusmind.service.AiTraceService;
import com.luky.nexusmind.service.ModelConfigService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// 嵌入向量生成客户端
@Component
public class EmbeddingClient {

    @Value("${embedding.api.batch-size:100}")
    private int batchSize;

    @Value("${embedding.api.concurrent-enabled:false}")
    private boolean concurrentEnabled;

    @Value("${embedding.api.max-concurrency:1}")
    private int maxConcurrency;

    @Value("${embedding.api.dimension:2048}")
    private int dimension;
    
    private static final Logger logger = LoggerFactory.getLogger(EmbeddingClient.class);
    private final ObjectMapper objectMapper;
    private final AiTraceService aiTraceService;
    private final ModelConfigService modelConfigService;

    public EmbeddingClient(ObjectMapper objectMapper,
                           AiTraceService aiTraceService,
                           ModelConfigService modelConfigService) {
        this.objectMapper = objectMapper;
        this.aiTraceService = aiTraceService;
        this.modelConfigService = modelConfigService;
    }

    /**
     * 调用通义千问 API 生成向量
     * @param texts 输入文本列表
     * @return 对应的向量列表
     */
    public List<float[]> embed(List<String> texts) {
        return embed(texts, null, null);
    }

    public List<float[]> embed(List<String> texts, String userId, String fileMd5) {
        ModelConfigService.ResolvedModelConfig modelConfig = modelConfigService.resolveEmbeddingConfig(userId);
        int effectiveBatchSize = modelConfig.batchSize() != null ? modelConfig.batchSize() : batchSize;
        int effectiveMaxConcurrency = modelConfig.maxConcurrency() != null ? modelConfig.maxConcurrency() : maxConcurrency;
        int effectiveDimension = modelConfig.dimension() != null ? modelConfig.dimension() : dimension;
        AiTraceService.TraceSpan span = fileMd5 != null
                ? aiTraceService.startFileSpan("embedding.batch", userId, fileMd5, null)
                : aiTraceService.startSpan("embedding.batch", null, null, null);
        span
                .attribute("gen_ai.operation.name", "embeddings")
                .attribute("gen_ai.request.model", modelConfig.modelName())
                .attribute("nexusmind.embedding.input.count", texts != null ? texts.size() : 0)
                .attribute("nexusmind.model.config.id", modelConfig.id() != null ? modelConfig.id() : -1)
                .attribute("nexusmind.embedding.batch_size", effectiveBatchSize)
                .attribute("nexusmind.embedding.concurrent_enabled", concurrentEnabled)
                .attribute("nexusmind.embedding.max_concurrency", effectiveMaxConcurrency)
                .attribute("nexusmind.embedding.dimension", effectiveDimension);
        try {
            logger.info("开始生成向量，文本数量: {}", texts.size());

            List<List<String>> batches = splitIntoBatches(texts, effectiveBatchSize);
            span.attribute("nexusmind.embedding.batch.count", batches.size());
            List<float[]> all;
            if (concurrentEnabled && effectiveMaxConcurrency > 1 && batches.size() > 1) {
                all = embedConcurrently(batches, texts.size(), effectiveMaxConcurrency, modelConfig, effectiveDimension);
            } else {
                all = embedSerially(batches, texts.size(), modelConfig, effectiveDimension);
            }

            logger.info("成功生成向量，总数量: {}", all.size());
            span.attribute("nexusmind.embedding.output.count", all.size());
            span.end();
            return all;
        } catch (Exception e) {
            logger.error("调用向量化 API 失败: {}", e.getMessage(), e);
            span.error(e);
            span.end();
            throw new RuntimeException("向量生成失败", e);
        } finally {
            span.close();
        }
    }

    private List<float[]> embedConcurrently(List<List<String>> batches,
                                            int expectedSize,
                                            int maxConcurrency,
                                            ModelConfigService.ResolvedModelConfig modelConfig,
                                            int dimension) throws Exception {
        int concurrency = Math.min(maxConcurrency, batches.size());
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        try {
            logger.info("启用并发向量化，批次数: {}, 并发数: {}", batches.size(), concurrency);
            List<CompletableFuture<List<float[]>>> futures = new ArrayList<>(batches.size());
            for (int i = 0; i < batches.size(); i++) {
                final int batchIndex = i;
                final List<String> batch = batches.get(i);
                futures.add(CompletableFuture.supplyAsync(() -> callAndParseBatch(batchIndex, batch, modelConfig, dimension), executor));
            }

            List<float[]> all = new ArrayList<>(expectedSize);
            for (CompletableFuture<List<float[]>> future : futures) {
                all.addAll(future.join());
            }
            return all;
        } catch (Exception e) {
            logger.warn("并发向量化失败，自动回退为串行请求: {}", e.getMessage());
            return embedSerially(batches, expectedSize, modelConfig, dimension);
        } finally {
            executor.shutdown();
        }
    }

    private List<float[]> embedSerially(List<List<String>> batches,
                                        int expectedSize,
                                        ModelConfigService.ResolvedModelConfig modelConfig,
                                        int dimension) throws Exception {
        logger.info("使用串行向量化，批次数: {}", batches.size());
        List<float[]> all = new ArrayList<>(expectedSize);
        for (int i = 0; i < batches.size(); i++) {
            all.addAll(callAndParseBatch(i, batches.get(i), modelConfig, dimension));
        }
        return all;
    }

    private List<float[]> callAndParseBatch(int batchIndex,
                                            List<String> batch,
                                            ModelConfigService.ResolvedModelConfig modelConfig,
                                            int dimension) {
        try {
            logger.debug("调用向量 API, 批次: {} (size={})", batchIndex, batch.size());
            String response = callApiOnce(batch, modelConfig, dimension);
            return parseVectors(response);
        } catch (WebClientResponseException e) {
            logger.error("向量化批次失败: batchIndex={}, status={}, responseBody={}",
                    batchIndex, e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("向量化批次失败: " + batchIndex, e);
        } catch (Exception e) {
            throw new RuntimeException("向量化批次失败: " + batchIndex, e);
        }
    }

    private List<List<String>> splitIntoBatches(List<String> texts, int batchSize) {
        List<List<String>> batches = new ArrayList<>((texts.size() + batchSize - 1) / batchSize);
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, texts.size());
            batches.add(texts.subList(start, end));
        }
        return batches;
    }

    private String callApiOnce(List<String> batch, ModelConfigService.ResolvedModelConfig modelConfig, int dimension) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelConfig.modelName());
        requestBody.put("input", batch);
        requestBody.put("dimensions", dimension);
        requestBody.put("encoding_format", "float");  // 添加编码格式

        return buildWebClient(modelConfig).post()
                .uri("/embeddings")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(1))
                        .filter(e -> e instanceof WebClientResponseException responseException
                                && responseException.getStatusCode().is5xxServerError()))
                .block(Duration.ofSeconds(30));
    }

    private WebClient buildWebClient(ModelConfigService.ResolvedModelConfig modelConfig) {
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(modelConfig.baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (modelConfig.apiKey() != null && !modelConfig.apiKey().trim().isEmpty()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + modelConfig.apiKey());
        }
        return builder.build();
    }

    private List<float[]> parseVectors(String response) throws Exception {
        JsonNode jsonNode = objectMapper.readTree(response);
        JsonNode data = jsonNode.get("data");  // 兼容模式下使用data字段
        if (data == null || !data.isArray()) {
            throw new RuntimeException("API 响应格式错误: data 字段不存在或不是数组");
        }
        
        List<float[]> vectors = new ArrayList<>();
        for (JsonNode item : data) {
            JsonNode embedding = item.get("embedding");
            if (embedding != null && embedding.isArray()) {
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vector[i] = (float) embedding.get(i).asDouble();
                }
                vectors.add(vector);
            }
        }
        return vectors;
    }
}
