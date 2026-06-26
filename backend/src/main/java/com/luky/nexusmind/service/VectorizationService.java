package com.luky.nexusmind.service;

import com.luky.nexusmind.client.EmbeddingClient;
import com.luky.nexusmind.model.DocumentVector;
import com.luky.nexusmind.model.ProcessingStage;
import com.luky.nexusmind.entity.EsDocument;
import com.luky.nexusmind.entity.TextChunk;
import com.luky.nexusmind.repository.DocumentVectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

// 向量化服务类
@Service
public class VectorizationService {

    private static final Logger logger = LoggerFactory.getLogger(VectorizationService.class);

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private AiTraceService aiTraceService;

    @Autowired
    private FileProcessingStatusService processingStatusService;

    @Autowired
    private ModelConfigService modelConfigService;

    /**
     * 执行向量化操作
     * @param fileMd5 文件指纹
     * @param userId 上传用户ID
     * @param orgTag 组织标签
     * @param isPublic 是否公开
     */
    public int vectorize(String fileMd5, String userId, String orgTag, boolean isPublic) {
        AiTraceService.TraceSpan span = aiTraceService.startFileSpan("file.vectorize", userId, fileMd5, null)
                .attribute("nexusmind.org_tag", orgTag)
                .attribute("nexusmind.upload.is_public", isPublic);
        try {
            logger.info("开始向量化文件，fileMd5: {}, userId: {}, orgTag: {}, isPublic: {}", 
                       fileMd5, userId, orgTag, isPublic);
                       
            // 获取文件分块内容
            List<TextChunk> chunks;
            AiTraceService.TraceSpan fetchSpan = aiTraceService.startFileSpan("file.vectorize.fetch_chunks", userId, fileMd5, null)
                    .attribute("db.system", "mysql");
            try {
                chunks = fetchTextChunks(fileMd5);
                fetchSpan.attribute("nexusmind.vectorize.chunk.count", chunks != null ? chunks.size() : 0);
            } catch (RuntimeException e) {
                fetchSpan.error(e);
                throw e;
            } finally {
                fetchSpan.end();
                fetchSpan.close();
            }
            if (chunks == null || chunks.isEmpty()) {
                logger.warn("未找到分块内容，fileMd5: {}", fileMd5);
                span.attribute("nexusmind.vectorize.status", "no_chunks");
                throw new IllegalStateException("未找到分块内容，无法向量化");
            }
            span.attribute("nexusmind.vectorize.chunk.count", chunks.size());

            // 提取文本内容
            List<String> texts = chunks.stream()
                    .map(TextChunk::getContent)
                    .toList();

            // 调用外部模型生成向量
            List<float[]> vectors = embeddingClient.embed(texts, userId, fileMd5);
            ModelConfigService.ResolvedModelConfig embeddingConfig = modelConfigService.resolveEmbeddingConfig(userId);
            span.attribute("nexusmind.vectorize.embedding.count", vectors.size());
            span.attribute("nexusmind.model.config.id", embeddingConfig.id() != null ? embeddingConfig.id() : -1);

            // 构建 Elasticsearch 文档并存储
            List<EsDocument> esDocuments;
            AiTraceService.TraceSpan buildSpan = aiTraceService.startFileSpan("file.vectorize.build_es_documents", userId, fileMd5, null);
            try {
                esDocuments = IntStream.range(0, chunks.size())
                        .mapToObj(i -> new EsDocument(
                                UUID.randomUUID().toString(),
                                fileMd5,
                                chunks.get(i).getChunkId(),
                                chunks.get(i).getContent(),
                                vectors.get(i),
                                embeddingConfig.modelName(),
                                userId,
                                orgTag,
                                isPublic
                        ))
                        .toList();
                buildSpan.attribute("nexusmind.elasticsearch.document.count", esDocuments.size());
            } catch (RuntimeException e) {
                buildSpan.error(e);
                throw e;
            } finally {
                buildSpan.end();
                buildSpan.close();
            }

            processingStatusService.markRunning(fileMd5, userId, ProcessingStage.INDEXING, "正在写入检索索引");
            elasticsearchService.bulkIndex(esDocuments); // 批量存储到 Elasticsearch

            span.attribute("nexusmind.vectorize.status", "success");
            logger.info("向量化完成，fileMd5: {}", fileMd5);
            return esDocuments.size();
        } catch (Exception e) {
            span.error(e);
            span.attribute("nexusmind.vectorize.status", "failed");
            logger.error("向量化失败，fileMd5: {}", fileMd5, e);
            throw new RuntimeException("向量化失败", e);
        } finally {
            span.end();
            span.close();
        }
    }
    

    /**
     * 获取文件分块内容
     * @param fileMd5 文件指纹
     * @return 分块内容列表
     */
    // 从数据库获取分块内容
    private List<TextChunk> fetchTextChunks(String fileMd5) {
        // 调用 Repository 查询数据
        List<DocumentVector> vectors = documentVectorRepository.findByFileMd5(fileMd5);

        // 转换为 TextChunk 列表
        return vectors.stream()
                .map(vector -> new TextChunk(
                        vector.getChunkId(),
                        vector.getTextContent()
                ))
                .toList();
    }
}
