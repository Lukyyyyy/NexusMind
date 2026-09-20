package com.luky.nexusmind.service;

import com.luky.nexusmind.config.KafkaConfig;
import com.luky.nexusmind.model.DocumentDeletionTask;
import com.luky.nexusmind.model.FileUpload;
import com.luky.nexusmind.repository.FileUploadRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentAsyncDeletionTest {
    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void requestMarksDocumentBeforePublishingCleanup() {
        FileUpload file = new FileUpload();
        file.setId(7L);
        file.setFileMd5("0123456789abcdef0123456789abcdef");
        file.setUserId("owner");
        var files = mock(FileUploadRepository.class);
        when(files.lockAllByMd5(file.getFileMd5())).thenReturn(List.of(file));
        var control = mock(FileTaskControl.class);
        var kafka = mock(KafkaTemplate.class);
        var config = mock(KafkaConfig.class);
        when(config.getDocumentDeletionTopic()).thenReturn("document-deletion-topic");
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(kafka.executeInTransaction(any())).thenAnswer(call -> {
            KafkaOperations.OperationsCallback<String, Object, Object> callback = call.getArgument(0);
            return callback.doInOperations(kafka);
        });
        DocumentService service = new DocumentService();
        ReflectionTestUtils.setField(service, "fileUploadRepository", files);
        ReflectionTestUtils.setField(service, "taskControl", control);
        ReflectionTestUtils.setField(service, "kafkaTemplate", kafka);
        ReflectionTestUtils.setField(service, "kafkaConfig", config);

        TransactionSynchronizationManager.initSynchronization();
        service.enqueueDocumentDeletion(file.getFileMd5(), file.getUserId());

        assertTrue(file.isDeletionPending());
        verify(files).saveAndFlush(file);
        verify(kafka).send(eq("document-deletion-topic"), eq(file.getFileMd5()),
                eq(new DocumentDeletionTask(7L, file.getFileMd5(), file.getUserId())));
        TransactionSynchronization synchronization = TransactionSynchronizationManager.getSynchronizations().get(0);
        synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        verify(control, never()).abortDelete(anyString(), anyString());
        synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(control).abortDelete(file.getFileMd5(), file.getUserId());
    }

    @Test
    void consumerIgnoresMessageWhoseDatabaseMarkerDidNotCommit() {
        FileUpload file = new FileUpload();
        file.setId(7L);
        file.setFileMd5("0123456789abcdef0123456789abcdef");
        file.setUserId("owner");
        var files = mock(FileUploadRepository.class);
        when(files.lockAllByMd5(file.getFileMd5())).thenReturn(List.of(file));
        var graph = mock(KnowledgeGraphService.class);
        DocumentService service = new DocumentService();
        ReflectionTestUtils.setField(service, "fileUploadRepository", files);
        ReflectionTestUtils.setField(service, "taskControl", mock(FileTaskControl.class));
        ReflectionTestUtils.setField(service, "knowledgeGraphService", graph);

        service.deleteDocument(new DocumentDeletionTask(7L, file.getFileMd5(), file.getUserId()));

        verifyNoInteractions(graph);
        verify(files, never()).delete(any());
    }
}
