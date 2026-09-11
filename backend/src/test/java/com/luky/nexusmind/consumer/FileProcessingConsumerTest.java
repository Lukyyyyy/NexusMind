package com.luky.nexusmind.consumer;

import com.luky.nexusmind.config.KafkaConfig;
import com.luky.nexusmind.model.*;
import com.luky.nexusmind.repository.DocumentVectorRepository;
import com.luky.nexusmind.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileProcessingConsumerTest {
    ParseService parse = mock(ParseService.class);
    VectorizationService vectors = mock(VectorizationService.class);
    AiTraceService traces = mock(AiTraceService.class);
    FileProcessingStatusService statuses = mock(FileProcessingStatusService.class);
    DocumentVectorRepository chunks = mock(DocumentVectorRepository.class);
    ElasticsearchService es = mock(ElasticsearchService.class);
    KnowledgeGraphExtractionService graph = mock(KnowledgeGraphExtractionService.class);
    UploadService uploads = mock(UploadService.class);
    ModelConfigService models = mock(ModelConfigService.class, RETURNS_DEEP_STUBS);
    FileProcessingTask task = new FileProcessingTask("md5", "http://expired.invalid/?signature=old", "paper.pdf", "user", "default", true);
    FileProcessingConsumer consumer;

    @BeforeEach void setup() {
        consumer = new FileProcessingConsumer(parse, vectors, traces, statuses, chunks, es, graph, uploads, models);
        ReflectionTestUtils.setField(consumer, "kafkaConfig", mock(KafkaConfig.class));
        ReflectionTestUtils.setField(consumer, "taskControl", mock(com.luky.nexusmind.service.FileTaskControl.class));
        var span = mock(AiTraceService.TraceSpan.class, RETURNS_SELF);
        when(traces.startFileSpanWithParent(any(), any(), any(), any(), any())).thenReturn(span);
        when(traces.startFileSpan(any(), any(), any(), any())).thenReturn(span);
        when(statuses.beginAttempt(task)).thenReturn(true);
        when(models.resolveEmbeddingConfig("user").modelName()).thenReturn("embedding");
    }

    @Test void cancellationDoesNotTriggerKafkaRetry() {
        when(statuses.beginAttempt(task)).thenThrow(new com.luky.nexusmind.service.FileTaskControl.Cancelled());
        assertDoesNotThrow(() -> consumer.processTask(task));
        verifyNoInteractions(parse, vectors, es, chunks, uploads, graph);
    }

    @Test void ignoresOldOrDeletedTaskBeforeAnySideEffects() {
        when(statuses.beginAttempt(task)).thenReturn(false);
        consumer.processTask(task);
        verifyNoInteractions(parse, vectors, es, chunks, uploads, graph);
    }

    @Test void databaseCheckpointOverridesStaleParsingMessage() {
        task.setResumeFromStage(ProcessingStage.PARSING);
        FileProcessingStatus status = new FileProcessingStatus();
        status.setCurrentStage(ProcessingStage.INDEXING);
        status.setLastSuccessfulStage(ProcessingStage.CHUNKING);
        status.setParsedChunkCount(31);
        when(statuses.findByFileMd5AndUserId("md5", "user")).thenReturn(Optional.of(status));
        when(chunks.countDistinctChunksByFileMd5("md5")).thenReturn(31L);
        when(chunks.findByFileMd5("md5")).thenReturn(List.of());
        when(es.countByFileMd5("md5")).thenReturn(31L);
        when(es.hasCompleteIndex(eq("md5"), anyList(), eq("embedding"))).thenReturn(true);
        consumer.processTask(task);
        verify(statuses).markCompleted(task, 31, 31L);
        verifyNoInteractions(uploads, parse, vectors);
        verify(es, never()).deleteByFileMd5(any());
    }

    @Test void equalCountButWrongContentsRebuildsIndexWithoutParsing() {
        FileProcessingStatus status = new FileProcessingStatus();
        status.setLastSuccessfulStage(ProcessingStage.CHUNKING);
        status.setParsedChunkCount(31);
        when(statuses.findByFileMd5AndUserId("md5", "user")).thenReturn(Optional.of(status));
        when(chunks.countDistinctChunksByFileMd5("md5")).thenReturn(31L);
        when(es.countByFileMd5("md5")).thenReturn(31L);
        when(vectors.vectorize("md5", "user", "default", true)).thenReturn(31);
        consumer.processTask(task);
        verify(es).deleteByFileMd5("md5");
        verify(vectors).vectorize("md5", "user", "default", true);
        verifyNoInteractions(uploads, parse);
    }

    @Test void incompleteStoredChunksCannotBecomeNewSuccessfulCheckpoint() {
        var status = new FileProcessingStatus();
        status.setLastSuccessfulStage(ProcessingStage.CHUNKING);
        status.setParsedChunkCount(31);
        when(statuses.findByFileMd5AndUserId("md5", "user")).thenReturn(Optional.of(status));
        when(chunks.countDistinctChunksByFileMd5("md5")).thenReturn(5L);
        when(uploads.openMergedFile("md5", "paper.pdf")).thenThrow(new IllegalStateException("storage unavailable"));
        assertThrows(RuntimeException.class, () -> consumer.processTask(task));
        verify(uploads).openMergedFile("md5", "paper.pdf");
        verify(es, never()).countByFileMd5(any());
        verify(statuses, never()).markCompleted(any(), anyInt(), anyLong());
    }

    @Test void expiredLegacyUrlIsNotUsedForDownload() throws Exception {
        when(statuses.findByFileMd5AndUserId("md5", "user")).thenReturn(Optional.empty());
        when(uploads.openMergedFile("md5", "paper.pdf")).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(parse.parseAndSave(eq("md5"), any(), eq("user"), eq("default"), eq(true), any(), eq("paper.pdf"), isNull())).thenReturn(1);
        when(vectors.vectorize("md5", "user", "default", true)).thenReturn(1);
        consumer.processTask(task);
        verify(uploads).openMergedFile("md5", "paper.pdf");
        verify(statuses).markCompleted(task, 1, 1L);
    }

    @Test void downloadFailureRecordsActualStageAndPreservesCause() {
        when(statuses.findByFileMd5AndUserId("md5", "user")).thenReturn(Optional.empty());
        var cause = new IllegalStateException("NoSuchKey");
        when(uploads.openMergedFile("md5", "paper.pdf")).thenThrow(cause);
        var failure = assertThrows(RuntimeException.class, () -> consumer.processTask(task));
        assertSame(cause, failure.getCause());
        verify(statuses).markRunning(task, ProcessingStage.PARSING, "正在读取原始文件");
        verifyNoInteractions(parse, vectors);
    }
}
