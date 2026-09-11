package com.luky.nexusmind.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import com.luky.nexusmind.model.*;
import com.luky.nexusmind.repository.FileUploadRepository;
import com.luky.nexusmind.repository.GraphCandidateRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

class KnowledgeGraphServiceTest {
    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMIN"})
    void onlyOwnerOrSuperAdminCanManageDocumentGraph(String role) {
        var files = mock(FileUploadRepository.class);
        var service = new KnowledgeGraphService(files, mock(GraphCandidateRepository.class),
                mock(KnowledgeGraphStoreService.class), mock(KnowledgeGraphExtractionService.class),
                mock(GraphPromptTemplateService.class));

        var error = org.junit.jupiter.api.Assertions.assertThrows(
                com.luky.nexusmind.exception.CustomException.class,
                () -> service.get("abc", "other-user", role));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        verify(files, never()).findByFileMd5("abc");
    }

    @Test
    void superAdminCanManageAnotherUsersDocumentGraph() {
        var files = mock(FileUploadRepository.class);
        var candidates = mock(GraphCandidateRepository.class);
        var store = mock(KnowledgeGraphStoreService.class);
        var templates = mock(GraphPromptTemplateService.class);
        var file = file("owner", "default", false);
        file.setId(7L);
        file.setFileMd5("abc");
        when(files.findByFileMd5("abc")).thenReturn(Optional.of(file));
        when(candidates.findByFileUploadIdOrderByEvidenceChunkIdAscIdAsc(7L)).thenReturn(List.of());
        when(templates.resolve(null)).thenReturn(new GraphPromptTemplateService.ResolvedTemplate(1L, "通用", ""));
        var service = new KnowledgeGraphService(files, candidates, store,
                mock(KnowledgeGraphExtractionService.class), templates);

        assertEquals("abc", service.get("abc", "root", "SUPER_ADMIN").fileMd5());
    }

    @Test
    void publishWritesOnlySelectedCandidatesAndMarksReviewComplete() {
        FileUploadRepository files = mock(FileUploadRepository.class);
        GraphCandidateRepository candidates = mock(GraphCandidateRepository.class);
        KnowledgeGraphStoreService store = mock(KnowledgeGraphStoreService.class);
        KnowledgeGraphExtractionService extraction = mock(KnowledgeGraphExtractionService.class);
        GraphPromptTemplateService templates = mock(GraphPromptTemplateService.class);
        when(templates.resolve(any()))
                .thenReturn(new GraphPromptTemplateService.ResolvedTemplate(1L, "通用", ""));
        KnowledgeGraphService service =
                new KnowledgeGraphService(files, candidates, store, extraction, templates);

        FileUpload file = new FileUpload();
        file.setId(7L);
        file.setFileMd5("abc");
        file.setUserId("jack");
        file.setGraphEnabled(true);
        file.setGraphStatus(KnowledgeGraphStatus.PENDING_REVIEW);
        GraphCandidate selected = candidate(1L, true);
        GraphCandidate rejected = candidate(2L, false);

        when(files.findByFileMd5AndUserId("abc", "jack")).thenReturn(Optional.of(file));
        when(candidates.findByFileUploadIdAndStatusAndSelectedTrueOrderByIdAsc(
                        7L, GraphCandidateStatus.PENDING))
                .thenReturn(List.of(selected));
        when(candidates.findByFileUploadIdOrderByEvidenceChunkIdAscIdAsc(7L))
                .thenReturn(List.of(selected, rejected));
        when(store.isEnabled()).thenReturn(true);

        KnowledgeGraphService.DocumentGraphResponse response =
                service.publish("abc", "jack", "USER");

        verify(store).publish(file, List.of(selected));
        assertEquals(GraphCandidateStatus.PUBLISHED, selected.getStatus());
        assertEquals(GraphCandidateStatus.REJECTED, rejected.getStatus());
        assertEquals(KnowledgeGraphStatus.PUBLISHED, response.status());
        assertEquals(2, response.nodes().size());
        assertEquals(1, response.edges().size());
        assertEquals("依赖", response.edges().get(0).predicate());
        // A response lost after commit must be safe to retry without another graph write.
        assertEquals(KnowledgeGraphStatus.PUBLISHED, service.publish("abc", "jack", "USER").status());
        verify(store, times(1)).publish(any(), any());
    }

    @Test
    void failedGraphWritePreservesPendingReviewAndReturnsActionableError() {
        var files = mock(FileUploadRepository.class);
        var candidates = mock(GraphCandidateRepository.class);
        var store = mock(KnowledgeGraphStoreService.class);
        var service = new KnowledgeGraphService(files, candidates, store,
                mock(KnowledgeGraphExtractionService.class), mock(GraphPromptTemplateService.class));
        var file = file("jack", "default", false);
        file.setId(7L);
        file.setGraphStatus(KnowledgeGraphStatus.PENDING_REVIEW);
        var selected = candidate(1L, true);
        when(files.findByFileMd5AndUserId("abc", "jack")).thenReturn(Optional.of(file));
        when(candidates.findByFileUploadIdAndStatusAndSelectedTrueOrderByIdAsc(7L, GraphCandidateStatus.PENDING))
                .thenReturn(List.of(selected));
        doThrow(new IllegalStateException("connection unavailable")).when(store).publish(any(), any());
        var error = org.junit.jupiter.api.Assertions.assertThrows(
                com.luky.nexusmind.exception.CustomException.class,
                () -> service.publish("abc", "jack", "USER"));
        assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
        assertEquals(KnowledgeGraphStatus.PENDING_REVIEW, file.getGraphStatus());
        assertEquals(GraphCandidateStatus.PENDING, selected.getStatus());
        verify(candidates, never()).saveAll(any());
        verify(files, never()).save(any());
    }

    @Test
    void normalizationIsConservativeAndIgnoresFormattingOnly() {
        assertEquals("nexusmind", KnowledgeGraphStoreService.normalizeName(" Nexus-Mind "));
        assertEquals("知枢nexusmind", KnowledgeGraphStoreService.normalizeName("知枢 · NexusMind"));
    }

    @Test
    void documentsArePartitionedIntoPublicOrganizationAndPersonalGraphs() {
        FileUpload publicFile = file("jack", "default", true);
        FileUpload organizationFile = file("jack", "研发部", false);
        FileUpload privateFile = file("jack", "PRIVATE_Jack", false);

        assertEquals(
                List.of("ORG_PUBLIC:default", "ORG_INTERNAL:default"),
                KnowledgeGraphStoreService.scopeIds(publicFile));
        assertEquals(
                List.of("ORG_INTERNAL:研发部"), KnowledgeGraphStoreService.scopeIds(organizationFile));
        assertEquals(List.of("PRIVATE:Jack"), KnowledgeGraphStoreService.scopeIds(privateFile));
    }

    @Test
    void manualRetryKeepsCheckpointAndCandidatesAndRejectsDuplicateStart() {
        var files = mock(FileUploadRepository.class);
        var candidates = mock(GraphCandidateRepository.class);
        var store = mock(KnowledgeGraphStoreService.class);
        var extraction = mock(KnowledgeGraphExtractionService.class);
        var templates = mock(GraphPromptTemplateService.class);
        var engine = mock(GraphExtractionEngine.class);
        when(templates.resolve(any()))
                .thenReturn(new GraphPromptTemplateService.ResolvedTemplate(1L, "通用", ""));
        var service = new KnowledgeGraphService(files, candidates, store, extraction, templates);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "engine", engine);
        var file = file("jack", "default", false);
        file.setId(7L);
        file.setFileMd5("abc");
        file.setGraphEnabled(true);
        file.setGraphStatus(KnowledgeGraphStatus.PENDING_REVIEW);
        when(files.findByFileMd5AndUserId("abc", "jack")).thenReturn(Optional.of(file));
        var count = new GraphExtractionEngine.StageProgress(2, 2, 1, 1, 0);
        when(engine.progress(7L))
                .thenReturn(
                        new GraphExtractionEngine.Progress(
                                "COMPLETE", count, count, 0, true, List.of()));
        service.retry("abc", "jack", "USER");
        verify(engine, never()).removeRun(any());
        verify(candidates, never()).deleteByFileUploadId(any());
        verify(extraction).retryAsync("abc", "jack");
        org.junit.jupiter.api.Assertions.assertThrows(
                com.luky.nexusmind.exception.CustomException.class,
                () -> service.retry("abc", "jack", "USER"));
    }

    @Test
    void fullRebuildResetsOldRunWithoutBlockingReplacementGeneration() {
        var files = mock(FileUploadRepository.class);
        var candidates = mock(GraphCandidateRepository.class);
        var store = mock(KnowledgeGraphStoreService.class);
        var extraction = mock(KnowledgeGraphExtractionService.class);
        var templates = mock(GraphPromptTemplateService.class);
        var engine = mock(GraphExtractionEngine.class);
        when(templates.resolve(any()))
                .thenReturn(new GraphPromptTemplateService.ResolvedTemplate(1L, "通用", ""));
        var service = new KnowledgeGraphService(files, candidates, store, extraction, templates);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "engine", engine);
        var file = file("jack", "default", false);
        file.setId(7L);
        file.setFileMd5("abc");
        file.setGraphEnabled(true);
        file.setGraphStatus(KnowledgeGraphStatus.FAILED);
        when(files.findByFileMd5AndUserId("abc", "jack")).thenReturn(Optional.of(file));

        service.rebuild("abc", "jack", "USER", 1L);

        verify(engine).resetRun(7L);
        verify(engine, never()).removeRun(any());
        verify(extraction).extractAsync("abc", "jack");
        assertEquals(KnowledgeGraphStatus.QUEUED, file.getGraphStatus());
    }

    private GraphCandidate candidate(long id, boolean selected) {
        GraphCandidate value = new GraphCandidate();
        value.setId(id);
        value.setFileUploadId(7L);
        value.setSubjectName("订单系统");
        value.setSubjectType("SYSTEM");
        value.setPredicate("依赖");
        value.setObjectName("Redis");
        value.setObjectType("SERVICE");
        value.setEvidenceChunkId(1);
        value.setEvidenceText("订单系统依赖 Redis");
        value.setSelected(selected);
        value.setStatus(GraphCandidateStatus.PENDING);
        return value;
    }

    private FileUpload file(String userId, String orgTag, boolean isPublic) {
        FileUpload value = new FileUpload();
        value.setUserId(userId);
        value.setOrgTag(orgTag);
        value.setPublic(isPublic);
        return value;
    }
}
