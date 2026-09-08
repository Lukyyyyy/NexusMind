package com.luky.nexusmind.repository;

import com.luky.nexusmind.model.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    @Query("""
            select s from ChatSession s
            where s.user.username = :username
              and s.deletedAt is null
              and s.title <> '新会话'
            order by s.updatedAt desc
            """)
    List<ChatSession> findHistoryByUsername(@Param("username") String username);

    Optional<ChatSession> findByIdAndUserUsernameAndDeletedAtIsNull(Long id, String username);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ChatSession s
            set s.title = :title
            where s.id = :id
              and s.deletedAt is null
              and s.titleGenerated = false
              and s.title = '新会话'
            """)
    int setFallbackTitleIfDefault(@Param("id") Long id, @Param("title") String title);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ChatSession s
            set s.title = :title, s.titleGenerated = true
            where s.id = :id
              and s.deletedAt is null
              and s.titleGenerated = false
            """)
    int setGeneratedTitleIfPending(@Param("id") Long id, @Param("title") String title);
}
