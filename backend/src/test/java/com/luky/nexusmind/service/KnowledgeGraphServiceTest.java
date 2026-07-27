package com.luky.nexusmind.service;

import com.luky.nexusmind.model.*;
import com.luky.nexusmind.repository.FileUploadRepository;
import com.luky.nexusmind.repository.GraphCandidateRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class KnowledgeGraphServiceTest {
    @Test
    void publishWritesOnlySelectedCandidatesAndMarksReviewComplete() {
        FileUploadRepository files = mock(FileUploadRepository.class);
        GraphCandidateRepository candidates = mock(GraphCandidateRepository.class);
        KnowledgeGraphStoreService store = mock(KnowledgeGraphStoreService.class);
        KnowledgeGraphExtractionService extraction = mock(KnowledgeGraphExtractionService.class);
        KnowledgeGraphService service = new KnowledgeGraphService(files, candidates, store, extraction);

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
                7L, GraphCandidateStatus.PENDING)).thenReturn(List.of(selected));
        when(candidates.findByFileUploadIdOrderByEvidenceChunkIdAscIdAsc(7L))
                .thenReturn(List.of(selected, rejected));
        when(store.isEnabled()).thenReturn(true);

        KnowledgeGraphService.DocumentGraphResponse response = service.publish("abc", "jack", "USER");

        verify(store).publish(file, List.of(selected));
        assertEquals(GraphCandidateStatus.PUBLISHED, selected.getStatus());
        assertEquals(GraphCandidateStatus.REJECTED, rejected.getStatus());
        assertEquals(KnowledgeGraphStatus.PUBLISHED, response.status());
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

        assertEquals("PUBLIC", KnowledgeGraphStoreService.scopeId(publicFile));
        assertEquals("ORG:研发部", KnowledgeGraphStoreService.scopeId(organizationFile));
        assertEquals("USER:jack", KnowledgeGraphStoreService.scopeId(privateFile));
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
