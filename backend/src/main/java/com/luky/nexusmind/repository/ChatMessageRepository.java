package com.luky.nexusmind.repository;

import com.luky.nexusmind.model.ChatMessage;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    List<ChatMessage> findTop20BySessionIdOrderByCreatedAtDesc(Long sessionId);

    boolean existsBySessionId(Long sessionId);

    boolean existsBySessionIdAndRoleIn(Long sessionId, List<String> roles);

    @Query("""
            select m
            from ChatMessage m
            join fetch m.session s
            join fetch s.user u
            where (:userId is null or u.id = :userId)
              and (:startDate is null or m.createdAt >= :startDate)
              and (:endDateExclusive is null or m.createdAt < :endDateExclusive)
            order by m.createdAt asc
            """)
    List<ChatMessage> findAuditMessages(@Param("userId") Long userId,
                                        @Param("startDate") LocalDateTime startDate,
                                        @Param("endDateExclusive") LocalDateTime endDateExclusive);
}
