package com.luky.nexusmind.consumer;

import com.luky.nexusmind.config.KafkaConfig;
import com.luky.nexusmind.model.FileProcessingTask;
import com.luky.nexusmind.model.ProcessingStage;
import com.luky.nexusmind.service.AiTraceService;
import com.luky.nexusmind.service.FileProcessingStatusService;
import com.luky.nexusmind.service.ParseService;
import com.luky.nexusmind.service.VectorizationService;
import io.minio.MinioClient;
import io.minio.errors.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

@Service
@Slf4j
public class FileProcessingConsumer {

    private final ParseService parseService;
    private final VectorizationService vectorizationService;
    private final AiTraceService aiTraceService;
    private final FileProcessingStatusService processingStatusService;
    @Autowired
    private KafkaConfig kafkaConfig;


    public FileProcessingConsumer(ParseService parseService, VectorizationService vectorizationService, AiTraceService aiTraceService,
                                  FileProcessingStatusService processingStatusService) {
        this.parseService = parseService;
        this.vectorizationService = vectorizationService;
        this.aiTraceService = aiTraceService;
        this.processingStatusService = processingStatusService;
    }

    @KafkaListener(topics = "#{kafkaConfig.getFileProcessingTopic()}", groupId = "#{kafkaConfig.getFileProcessingGroupId()}")
    public void processTask(FileProcessingTask task) {
        log.info("Received task: {}", task);
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
            // 下载文件
            AiTraceService.TraceSpan downloadSpan = aiTraceService.startFileSpan(
                    "file.storage.download", task.getUserId(), task.getFileMd5(), task.getFileName())
                    .attribute("storage.path.type", resolvePathType(task.getFilePath()));
            try {
                fileStream = downloadFileFromStorage(task.getFilePath());
            } catch (Exception e) {
                downloadSpan.error(e);
                throw e;
            } finally {
                downloadSpan.end();
                downloadSpan.close();
            }
            // 在 downloadFileFromStorage 返回后立即检查流是否可读
            if (fileStream == null) {
                throw new IOException("流为空");
            }

            // 强制转换为可缓存流
            if (!fileStream.markSupported()) {
                fileStream = new BufferedInputStream(fileStream);
            }

            // 解析文件
            processingStatusService.markRunning(task, ProcessingStage.PARSING, "正在解析文件");
            int parsedChunkCount = parseService.parseAndSave(task.getFileMd5(), fileStream,
                    task.getUserId(), task.getOrgTag(), task.isPublic(), task.getParseEngine(), task.getFileName(), task.getChunkSize());
            processingStatusService.markParsed(task, parsedChunkCount);
            log.info("文件解析完成，fileMd5: {}", task.getFileMd5());

            // 向量化处理
            processingStatusService.markRunning(task, ProcessingStage.VECTORIZING, "正在生成向量");
            int vectorizedCount = vectorizationService.vectorize(task.getFileMd5(),
                    task.getUserId(), task.getOrgTag(), task.isPublic());
            long esDocumentCount = vectorizedCount > 0 ? vectorizedCount : 0;
            processingStatusService.markCompleted(task, vectorizedCount, esDocumentCount);
            log.info("向量化完成，fileMd5: {}", task.getFileMd5());
            span.attribute("nexusmind.file.processing.status", "success");
        } catch (Exception e) {
            span.error(e);
            span.attribute("nexusmind.file.processing.status", "failed");
            log.error("Error processing task: {}", task, e);
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

    private String resolvePathType(String filePath) {
        if (filePath == null) {
            return "unknown";
        }
        if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
            return "url";
        }
        return "filesystem";
    }

    /**
     * 模拟从存储系统下载文件
     *
     * @param filePath 文件路径或 URL
     * @return 文件输入流
     */
    private InputStream downloadFileFromStorage(String filePath) throws ServerException, InsufficientDataException, ErrorResponseException, IOException, NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException, InternalException {
        log.info("Downloading file from storage: {}", filePath);

        try {
            // 如果是文件系统路径
            File file = new File(filePath);
            if (file.exists()) {
                log.info("Detected file system path: {}", filePath);
                return new FileInputStream(file);
            }

            // 如果是远程 URL
            if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
                log.info("Detected remote URL: {}", filePath);
                URL url = new URL(filePath);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(30000); // 连接超时30秒
                connection.setReadTimeout(180000);   // 读取超时时间3分钟

                // 添加必要的请求头
                connection.setRequestProperty("User-Agent", "NexusMind-FileProcessor/1.0");

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    log.info("Successfully connected to URL, starting download...");
                    return connection.getInputStream();
                } else if (responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                    log.error("Access forbidden - possible expired presigned URL");
                    throw new IOException("Access forbidden - the presigned URL may have expired");
                } else {
                    log.error("Failed to download file, HTTP response code: {} for URL: {}", responseCode, filePath);
                    throw new IOException(String.format("Failed to download file, HTTP response code: %d", responseCode));
                }
            }

            // 如果既不是文件路径也不是 URL
            throw new IllegalArgumentException("Unsupported file path format: " + filePath);
        } catch (Exception e) {
            log.error("Error downloading file from storage: {}", filePath, e);
            return null; // 或者抛出异常
        }
    }
}
