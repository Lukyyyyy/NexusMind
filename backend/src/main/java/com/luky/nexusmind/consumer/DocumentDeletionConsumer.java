package com.luky.nexusmind.consumer;

import com.luky.nexusmind.config.KafkaConfig;
import com.luky.nexusmind.model.DocumentDeletionTask;
import com.luky.nexusmind.service.DocumentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class DocumentDeletionConsumer {
    private final DocumentService documents;

    public DocumentDeletionConsumer(DocumentService documents) {
        this.documents = documents;
    }

    @KafkaListener(
            topics = "#{kafkaConfig.getDocumentDeletionTopic()}",
            groupId = "#{kafkaConfig.getDocumentDeletionGroupId()}")
    public void delete(DocumentDeletionTask task) {
        log.info("开始异步清理文档: fileMd5={}, userId={}", task.fileMd5(), task.userId());
        documents.deleteDocument(task);
    }
}
