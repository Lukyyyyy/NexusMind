package com.luky.nexusmind.repository;

import com.luky.nexusmind.model.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    List<ChatSession> findByUserUsernameAndDeletedAtIsNullOrderByUpdatedAtDesc(String username);

    Optional<ChatSession> findByIdAndUserUsernameAndDeletedAtIsNull(Long id, String username);
}
