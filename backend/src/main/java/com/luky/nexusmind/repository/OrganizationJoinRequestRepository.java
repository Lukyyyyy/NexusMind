package com.luky.nexusmind.repository;

import com.luky.nexusmind.model.OrganizationJoinRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrganizationJoinRequestRepository extends JpaRepository<OrganizationJoinRequest, Long> {
    Optional<OrganizationJoinRequest> findFirstByUserIdAndOrganizationTagIdAndStatus(Long userId, String tagId,
                                                                                      OrganizationJoinRequest.Status status);
    Optional<OrganizationJoinRequest> findFirstByUserIdAndOrganizationTagIdOrderByCreatedAtDesc(Long userId, String tagId);
    Page<OrganizationJoinRequest> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
    Page<OrganizationJoinRequest> findByStatusOrderByCreatedAtAsc(OrganizationJoinRequest.Status status, Pageable pageable);
    Page<OrganizationJoinRequest> findAllByOrderByCreatedAtDesc(Pageable pageable);
    List<OrganizationJoinRequest> findByOrganizationTagIdAndStatus(String tagId, OrganizationJoinRequest.Status status);
    List<OrganizationJoinRequest> findByOrganizationTagId(String tagId);
    long countByStatus(OrganizationJoinRequest.Status status);
    long countByUserIdAndCreatedAtAfter(Long userId, LocalDateTime after);
    boolean existsByOrganizationTagId(String tagId);
}
