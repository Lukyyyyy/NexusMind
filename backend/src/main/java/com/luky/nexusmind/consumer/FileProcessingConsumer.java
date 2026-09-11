package com.luky.nexusmind.consumer;

import com.luky.nexusmind.config.KafkaConfig;
import com.luky.nexusmind.model.FileProcessingTask;
import com.luky.nexusmind.model.FileProcessingStatus;
import com.luky.nexusmind.model.ProcessingStage;
import com.luky.nexusmind.repository.DocumentVectorRepository;
import com.luky.nexusmind.service.AiTraceService;
import com.luky.nexusmind.service.ElasticsearchService;
import com.luky.nexusmind.service.FileProcessingStatusService;
import com.luky.nexusmind.service.KnowledgeGraphExtractionService;
import com.luky.nexusmind.service.ParseService;
import com.luky.nexusmind.service.VectorizationService;
import com.luky.nexusmind.service.UploadService;
import com.luky.nexusmind.service.ModelConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.io.*;

@Service
@Slf4j
public class FileProcessingConsumer {

    private final UploadService uploadService;
    private final ModelConfigService modelConfigService;
    private final ParseService parseService;
    private final VectorizationService vectorizationService;
    private final AiTraceService aiTraceService;
    private final FileProcessingStatusService processingStatusService;
    private final DocumentVectorRepository documentVectorRepository;
    private final ElasticsearchService elasticsearchService;
    private final KnowledgeGraphExtractionService graphExtractionService;
    @Autowired
    private KafkaConfig kafkaConfig;
    @Autowired
    private com.luky.nexusmind.service.FileTaskControl taskControl;


    public FileProcessingConsumer(ParseService parseService, VectorizationService vectorizationService, AiTraceService aiTraceService,
                                  FileProcessingStatusService processingStatusService,
                                  DocumentVectorRepository documentVectorRepository,
                                  ElasticsearchService elasticsearchService,
                                  KnowledgeGraphExtractionService graphExtractionService,
                                  UploadService uploadService, ModelConfigService modelConfigService) {
        this.uploadService = uploadService;
        this.modelConfigService = modelConfigService;
        this.parseService = parseService;
        this.vectorizationService = vectorizationService;
        this.aiTraceService = aiTraceService;
        this.processingStatusService = processingStatusService;
        this.documentVectorRepository = documentVectorRepository;
        this.elasticsearchService = elasticsearchService;
        this.graphExtractionService = graphExtractionService;
    }

    @KafkaListener(topics = "#{kafkaConfig.getFileProcessingTopic()}", groupId = "#{kafkaConfig.getFileProcessingGroupId()}")
    public void processTask(FileProcessingTask task) {
        try (var scope = taskControl.open(task.getFileMd5(), task.getUserId(), null, task)) {
            processActiveTask(task);
        } catch (RuntimeException e) {
            if (!com.luky.nexusmind.service.FileTaskControl.isCancelled(e)) throw e;
            log.info("文件任务已取消，停止处理并确认消息，fileMd5: {}", task.getFileMd5());
        }
    }

    private void processActiveTask(FileProcessingTask task) {
        if (!com.luky.nexusmind.service.FileTaskControl.write(() -> processingStatusService.beginAttempt(task))) {
            log.info("忽略旧版本或已删除的文件任务，fileMd5: {}", task.getFileMd5());
            return;
        }
        log.info("开始处理文件任务，fileMd5: {}, attemptId: {}", task.getFileMd5(), task.getAttemptId());
        log.info("文件权限信息: userId={}, orgTag={}, isPublic={}", 
                task.getUserId(), task.getOrgTag(), task.isPublic());
                
        InputStream fileStream = null;
        AiTraceService.TraceSpan span = aiTraceService.startFileSpanWithParent(
                "file.kafka.consume",
                task.getTraceparent(),
                task.getUserId(),
                task.getFileMd5(),
                task.getFileName())
                .attribute("messaging.system", "kafka")
                .attribute("messaging.destination.name", kafkaConfig.getFileProcessingTopic())
                .attribute("nexusmind.org_tag", task.getOrgTag())
                .attribute("nexusmind.upload.is_public", task.isPublic())
                .attribute("nexusmind.parse.requested_engine", task.getParseEngine() != null ? task.getParseEngine().name() : "AUTO");
        if (task.getChunkSize() != null) {
            span.attribute("nexusmind.parse.chunk_size", task.getChunkSize());
        }
        try {
            FileProcessingStatus checkpoint = processingStatusService
                    .findByFileMd5AndUserId(task.getFileMd5(), task.getUserId()).orElse(null);
            ProcessingStage checkpointStage = checkpoint == null ? null
                    : checkpoint.getLastSuccessfulStage() != null ? checkpoint.getLastSuccessfulStage()
                    : checkpoint.getCurrentStage();
            long existingChunkCount = documentVectorRepository.countDistinctChunksByFileMd5(task.getFileMd5());
            boolean canReuseParsedChunks = canReuseParsedChunks(checkpointStage, existingChunkCount)
                    && checkpoint.getParsedChunkCount() != null
                    && checkpoint.getParsedChunkCount().longValue() == existingChunkCount;
            span.attribute("nexusmind.file.retry.checkpoint_stage",
                            checkpointStage == null ? "none" : checkpointStage.name())
                    .attribute("nexusmind.file.retry.existing_chunk_count", existingChunkCount)
                    .attribute("nexusmind.file.retry.reuse_parsed_chunks", canReuseParsedChunks);

            if (canReuseParsedChunks) {
                long existingEsDocumentCount = elasticsearchService.countByFileMd5(task.getFileMd5());
                if (existingEsDocumentCount == existingChunkCount && elasticsearchService.hasCompleteIndex(
                        task.getFileMd5(), documentVectorRepository.findByFileMd5(task.getFileMd5()),
                        modelConfigService.resolveEmbeddingConfig(task.getUserId()).modelName())) {
                    com.luky.nexusmind.service.FileTaskControl.write(() -> processingStatusService.markCompleted(task, Math.toIntExact(existingEsDocumentCount),
                            existingEsDocumentCount));
                    log.info("文件已完整入库，跳过重复处理，fileMd5: {}, chunks: {}",
                            task.getFileMd5(), existingChunkCount);
                    span.attribute("nexusmind.file.processing.status", "already_completed");
                    return;
                }
                com.luky.nexusmind.service.FileTaskControl.write(() -> processingStatusService.markParsed(task, Math.toIntExact(existingChunkCount)));
                log.info("复用已完成的解析切片，fileMd5: {}, chunks: {}",
                        task.getFileMd5(), existingChunkCount);
            } else {
                // 在下载前记录真实执行阶段，失败时不再沿用旧的 INDEXING 标签。
                com.luky.nexusmind.service.FileTaskControl.write(() -> processingStatusService.markRunning(task, ProcessingStage.PARSING, "正在读取原始文件"));
                // 使用稳定对象名读取，兼容历史消息中已经过期的签名 URL。
                AiTraceService.TraceSpan downloadSpan = aiTraceService.startFileSpan(
                        "file.storage.download", task.getUserId(), task.getFileMd5(), task.getFileName())
                        .attribute("storage.path.type", resolvePathType(task.getFilePath()));
                try {
                    fileStream = uploadService.openMergedFile(task.getFileMd5(), task.getFileName());
                } catch (Exception e) {
                    downloadSpan.error(e);
                    throw e;
                } finally {
                    downloadSpan.end();
                    downloadSpan.close();
                }
                if (fileStream == null) {
                    throw new IOException("流为空");
                }
                if (!fileStream.markSupported()) {
                    fileStream = new BufferedInputStream(fileStream);
                }

                com.luky.nexusmind.service.FileTaskControl.write(() -> processingStatusService.markRunning(task, ProcessingStage.PARSING, "正在解析文件"));
                int parsedChunkCount = parseService.parseAndSave(task.getFileMd5(), fileStream,
                        task.getUserId(), task.getOrgTag(), task.isPublic(), task.getParseEngine(),
                        task.getFileName(), task.getChunkSize());
                com.luky.nexusmind.service.FileTaskControl.write(() -> processingStatusService.markParsed(task, parsedChunkCount));
                log.info("文件解析完成，fileMd5: {}", task.getFileMd5());
            }

            // 图谱抽取使用已生成的切片异步执行，不阻塞现有向量化和入库流程。
            com.luky.nexusmind.service.FileTaskControl.check();
            graphExtractionService.extractAsync(task.getFileMd5(), task.getUserId());

            // 向量不保存中间结果。重试向量化或入库前清理可能残留的 ES 文档，
            // 然后对全部切片重新向量化，保证索引内容一致。
            com.luky.nexusmind.service.FileTaskControl.write(() -> processingStatusService.markRunning(task, ProcessingStage.VECTORIZING, "正在生成向量"));
            com.luky.nexusmind.service.FileTaskControl.write(() -> elasticsearchService.deleteByFileMd5(task.getFileMd5()));
            int vectorizedCount = vectorizationService.vectorize(task.getFileMd5(),
                    task.getUserId(), task.getOrgTag(), task.isPublic());
            long esDocumentCount = vectorizedCount > 0 ? vectorizedCount : 0;
            com.luky.nexusmind.service.FileTaskControl.write(() -> processingStatusService.markCompleted(task, vectorizedCount, esDocumentCount));
            log.info("向量化完成，fileMd5: {}", task.getFileMd5());
            span.attribute("nexusmind.file.processing.status", "success");
        } catch (Exception e) {
            span.error(e);
            span.attribute("nexusmind.file.processing.status", "failed");
            log.error("文件处理失败，fileMd5: {}, attemptId: {}", task.getFileMd5(), task.getAttemptId(), e);
            // 抛出异常让 Kafka 的 DefaultErrorHandler 捕获并触发重试 / 死信
            throw new RuntimeException("Error processing task", e);
        } finally {
            // 确保关闭输入流
            if (fileStream != null) {
                try {
                    fileStream.close();
                } catch (IOException e) {
                    log.error("Error closing file stream", e);
                }
            }
            span.end();
            span.close();
        }
    }

    private boolean canReuseParsedChunks(ProcessingStage checkpointStage, long existingChunkCount) {
        if (existingChunkCount <= 0 || checkpointStage == null) {
            return false;
        }
        return checkpointStage == ProcessingStage.CHUNKING
                || checkpointStage == ProcessingStage.VECTORIZING
                || checkpointStage == ProcessingStage.INDEXING
                || checkpointStage == ProcessingStage.COMPLETED;
    }

    private String resolvePathType(String filePath) {
        if (filePath == null) {
            return "unknown";
        }
        if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
            return "url";
        }
        return "filesystem";
    }

}
