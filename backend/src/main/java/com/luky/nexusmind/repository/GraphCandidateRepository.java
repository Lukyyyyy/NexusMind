package com.luky.nexusmind.repository;

import com.luky.nexusmind.model.GraphCandidate;
import com.luky.nexusmind.model.GraphCandidateStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

public interface GraphCandidateRepository extends JpaRepository<GraphCandidate, Long> {
    List<GraphCandidate> findByFileUploadIdOrderByEvidenceChunkIdAscIdAsc(Long fileUploadId);
    List<GraphCandidate> findByFileUploadIdAndStatusAndSelectedTrueOrderByIdAsc(
            Long fileUploadId, GraphCandidateStatus status);
    List<GraphCandidate> findByIdInAndFileUploadId(Collection<Long> ids, Long fileUploadId);
    long countByFileUploadIdAndStatus(Long fileUploadId, GraphCandidateStatus status);
    @Transactional
    void deleteByFileUploadId(Long fileUploadId);
}
