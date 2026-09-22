package com.luky.nexusmind.config;

import com.luky.nexusmind.model.FileUpload;
import com.luky.nexusmind.repository.FileUploadRepository;
import com.luky.nexusmind.service.UploadService;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/** 为只保存了历史 MD5 的文档回填 SHA-256；任务幂等，可安全跨多次启动继续执行。 */
@Component
@Order(20)
@ConditionalOnProperty(name = "nexusmind.upload.backfill-sha256", havingValue = "true", matchIfMissing = true)
public class DocumentSha256Backfill implements ApplicationRunner {
    private static final Logger logger = LoggerFactory.getLogger(DocumentSha256Backfill.class);

    private final FileUploadRepository files;
    private final MinioClient minio;
    private final String bucket;

    public DocumentSha256Backfill(FileUploadRepository files,
                                  MinioClient minio,
                                  @Value("${minio.bucketName}") String bucket) {
        this.files = files;
        this.minio = minio;
        this.bucket = bucket;
    }

    @Override
    public void run(ApplicationArguments args) {
        var pending = files.findByContentSha256IsNullAndDeletionPendingFalse();
        if (pending.isEmpty()) return;

        int completed = 0;
        int failed = 0;
        for (FileUpload file : pending) {
            try {
                String sha256 = digest(file);
                file.setContentSha256(sha256);
                files.saveAndFlush(file);
                completed++;
            } catch (Exception error) {
                failed++;
                logger.error("历史文档 SHA-256 回填失败: fileId={}, documentKey={}, fileName={}",
                        file.getId(), file.getFileMd5(), file.getFileName(), error);
            }
        }
        logger.info("历史文档 SHA-256 回填结束: completed={}, failed={}", completed, failed);
    }

    private String digest(FileUpload file) throws Exception {
        Exception failure = null;
        for (String object : UploadService.mergedObjectCandidates(file.getFileMd5(), file.getFileName())) {
            try (InputStream stream = minio.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(object)
                    .build())) {
                return DigestUtils.sha256Hex(stream);
            } catch (Exception error) {
                failure = error;
            }
        }
        throw new IllegalStateException("未找到可读取的历史文件对象", failure);
    }
}
