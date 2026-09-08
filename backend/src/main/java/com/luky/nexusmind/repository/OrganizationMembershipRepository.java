package com.luky.nexusmind.repository;

import com.luky.nexusmind.model.OrganizationMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface OrganizationMembershipRepository extends JpaRepository<OrganizationMembership, Long> {
    @Query("select m from OrganizationMembership m join fetch m.organization where m.user.id = :userId")
    List<OrganizationMembership> findByUserId(Long userId);
    List<OrganizationMembership> findByOrganizationTagId(String tagId);
    Optional<OrganizationMembership> findByUserIdAndOrganizationTagId(Long userId, String tagId);
    boolean existsByUserIdAndOrganizationTagId(Long userId, String tagId);
    long countByOrganizationTagId(String tagId);

    @Query("select m.organization.tagId from OrganizationMembership m where m.user.id = :userId")
    List<String> findTagIdsByUserId(Long userId);
}
