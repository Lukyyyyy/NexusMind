package com.luky.nexusmind.service;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.model.ChatMessage;
import com.luky.nexusmind.model.ChatSession;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.ChatMessageRepository;
import com.luky.nexusmind.repository.ChatSessionRepository;
import com.luky.nexusmind.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChatSessionService {

    private static final int MAX_TITLE_LENGTH = 60;

    private final UserRepository userRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;

    public ChatSessionService(UserRepository userRepository,
                              ChatSessionRepository chatSessionRepository,
                              ChatMessageRepository chatMessageRepository) {
        this.userRepository = userRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
    }

    @Transactional
    public ChatSession createSession(String username) {
        User user = getUser(username);
        ChatSession session = new ChatSession();
        session.setUser(user);
        session.setTitle("新会话");
        session.setTitleGenerated(false);
        return chatSessionRepository.save(session);
    }

    @Transactional(readOnly = true)
    public List<ChatSession> listSessions(String username) {
        return chatSessionRepository.findByUserUsernameAndDeletedAtIsNullOrderByUpdatedAtDesc(username);
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> getMessages(String username, Long sessionId) {
        ChatSession session = getOwnedActiveSession(username, sessionId);
        return chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
    }

    @Transactional
    public ChatSession renameSession(String username, Long sessionId, String title) {
        ChatSession session = getOwnedActiveSession(username, sessionId);
        String normalizedTitle = normalizeTitle(title);
        session.setTitle(normalizedTitle);
        session.setTitleGenerated(true);
        return chatSessionRepository.save(session);
    }

    @Transactional
    public void deleteSession(String username, Long sessionId) {
        ChatSession session = getOwnedActiveSession(username, sessionId);
        session.setDeletedAt(LocalDateTime.now());
        chatSessionRepository.save(session);
    }

    @Transactional(readOnly = true)
    public ChatSession getOwnedActiveSession(String username, Long sessionId) {
        return chatSessionRepository.findByIdAndUserUsernameAndDeletedAtIsNull(sessionId, username)
                .orElseThrow(() -> new CustomException("会话不存在或无权访问", HttpStatus.NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<Map<String, String>> getRecentHistory(String username, Long sessionId, int limit) {
        ChatSession session = getOwnedActiveSession(username, sessionId);
        List<ChatMessage> latestMessages = chatMessageRepository.findTop20BySessionIdOrderByCreatedAtDesc(session.getId());
        return latestMessages.stream()
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt))
                .limit(Math.max(0, limit))
                .map(message -> {
                    Map<String, String> item = new HashMap<>();
                    item.put("role", message.getRole());
                    item.put("content", message.getContent());
                    return item;
                })
                .toList();
    }

    @Transactional
    public boolean appendCompletedExchange(String username,
                                           Long sessionId,
                                           String userMessage,
                                           String assistantResponse,
                                           String generatedTitle) {
        ChatSession session = getOwnedActiveSession(username, sessionId);
        boolean wasEmpty = !chatMessageRepository.existsBySessionId(session.getId());
        chatMessageRepository.save(newMessage(session, "user", userMessage, "finished"));
        chatMessageRepository.save(newMessage(session, "assistant", assistantResponse, "finished"));
        if (wasEmpty) {
            session.setTitle(normalizeGeneratedTitle(generatedTitle, userMessage));
            session.setTitleGenerated(true);
        }
        chatSessionRepository.save(session);
        return wasEmpty;
    }

    @Transactional
    public boolean updateGeneratedTitle(String username, Long sessionId, String generatedTitle) {
        String normalized = normalizeGeneratedTitleOnly(generatedTitle);
        if (normalized == null) {
            return false;
        }
        ChatSession session = getOwnedActiveSession(username, sessionId);
        session.setTitle(normalized);
        session.setTitleGenerated(true);
        chatSessionRepository.save(session);
        return true;
    }

    private User getUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
    }

    private ChatMessage newMessage(ChatSession session, String role, String content, String status) {
        ChatMessage message = new ChatMessage();
        message.setSession(session);
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        message.setStatus(status);
        return message;
    }

    private String normalizeTitle(String title) {
        String normalized = title == null ? "" : title.trim();
        if (normalized.isEmpty()) {
            throw new CustomException("会话标题不能为空", HttpStatus.BAD_REQUEST);
        }
        return abbreviate(normalized, MAX_TITLE_LENGTH);
    }

    private String normalizeGeneratedTitle(String generatedTitle, String fallback) {
        String candidate = generatedTitle == null ? "" : generatedTitle
                .replace("\"", "")
                .replace("'", "")
                .replace("“", "")
                .replace("”", "")
                .trim();
        if (candidate.isEmpty()) {
            candidate = fallback == null ? "新会话" : fallback.trim();
        }
        if (candidate.isEmpty()) {
            candidate = "新会话";
        }
        return abbreviate(candidate, MAX_TITLE_LENGTH);
    }

    private String normalizeGeneratedTitleOnly(String generatedTitle) {
        String candidate = generatedTitle == null ? "" : generatedTitle
                .replace("\"", "")
                .replace("'", "")
                .replace("“", "")
                .replace("”", "")
                .trim();
        if (candidate.isEmpty()) {
            return null;
        }
        return abbreviate(candidate, MAX_TITLE_LENGTH);
    }

    private String abbreviate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
