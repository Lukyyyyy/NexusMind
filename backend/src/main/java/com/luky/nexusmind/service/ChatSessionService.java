package com.luky.nexusmind.service;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.model.ChatMessage;
import com.luky.nexusmind.model.ChatSession;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.ChatMessageRepository;
import com.luky.nexusmind.repository.ChatSessionRepository;
import com.luky.nexusmind.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChatSessionService {

    private static final int MAX_TITLE_LENGTH = 60;
    private static final int MAX_FALLBACK_TITLE_LENGTH = 120;
    private static final List<String> CONVERSATION_ROLES = List.of("user", "assistant");

    private final UserRepository userRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatHistoryCache chatHistoryCache;
    private final ModelConfigService modelConfigService;

    @Autowired
    public ChatSessionService(UserRepository userRepository,
                              ChatSessionRepository chatSessionRepository,
                              ChatMessageRepository chatMessageRepository,
                              ChatHistoryCache chatHistoryCache,
                              ModelConfigService modelConfigService) {
        this.userRepository = userRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.chatHistoryCache = chatHistoryCache;
        this.modelConfigService = modelConfigService;
    }

    public ChatSessionService(UserRepository userRepository,
                              ChatSessionRepository chatSessionRepository,
                              ChatMessageRepository chatMessageRepository) {
        this(userRepository, chatSessionRepository, chatMessageRepository, ChatHistoryCache.noop(), null);
    }

    public ChatSessionService(UserRepository userRepository,
                              ChatSessionRepository chatSessionRepository,
                              ChatMessageRepository chatMessageRepository,
                              ChatHistoryCache chatHistoryCache) {
        this(userRepository, chatSessionRepository, chatMessageRepository, chatHistoryCache, null);
    }

    @Transactional
    public ChatSession createSession(String username) {
        return createSession(username, new ChatScopeService.ScopeSelection(
                com.luky.nexusmind.model.ChatScopeType.ALL, null, "全部知识", null));
    }

    @Transactional
    public ChatSession createSession(String username, ChatScopeService.ScopeSelection scope) {
        User user = getUser(username);
        ChatSession session = new ChatSession();
        session.setUser(user);
        session.setTitle("新会话");
        session.setTitleGenerated(false);
        if (modelConfigService != null) applyModel(session, modelConfigService.resolveLlmConfig(username));
        applyScope(session, scope);
        return chatSessionRepository.save(session);
    }

    @Transactional
    public ChatSession changeModel(String username, Long sessionId, Long modelConfigId) {
        if (modelConfigService == null) throw new IllegalStateException("模型配置服务不可用");
        ChatSession session = getOwnedActiveSession(username, sessionId);
        ModelConfigService.ResolvedModelConfig model = modelConfigService.selectLlmConfig(username, modelConfigId);
        if (java.util.Objects.equals(session.getLlmConfigId(), model.id())) return session;
        boolean started = chatMessageRepository.existsBySessionIdAndRoleIn(sessionId, CONVERSATION_ROLES);
        applyModel(session, model);
        chatSessionRepository.save(session);
        if (started) chatMessageRepository.save(newMessage(session, "model", model.modelName(), "finished"));
        return session;
    }

    @Transactional
    public ModelConfigService.ResolvedModelConfig resolveSessionModel(String username, ChatSession session) {
        if (modelConfigService == null) return null;
        ModelConfigService.ResolvedModelConfig model;
        try {
            model = modelConfigService.resolveLlmConfig(username, session.getLlmConfigId());
        } catch (CustomException ignored) {
            model = modelConfigService.resolveLlmConfig(username);
        }
        if (!java.util.Objects.equals(session.getLlmConfigId(), model.id())
                || !java.util.Objects.equals(session.getLlmModelName(), model.modelName())) {
            applyModel(session, model);
            chatSessionRepository.save(session);
        }
        return model;
    }

    @Transactional
    public ChatSession updateScope(String username, Long sessionId, ChatScopeService.ScopeSelection scope) {
        ChatSession session = getOwnedActiveSession(username, sessionId);
        if (chatMessageRepository.existsBySessionIdAndRoleIn(sessionId, CONVERSATION_ROLES)) {
            throw new CustomException("已有消息的会话不能修改问答范围，请创建新会话", HttpStatus.CONFLICT);
        }
        applyScope(session, scope);
        return chatSessionRepository.save(session);
    }

    @Transactional(readOnly = true)
    public List<ChatSession> listSessions(String username) {
        return chatSessionRepository.findHistoryByUsername(username);
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
        afterCommit(() -> chatHistoryCache.evict(sessionId));
    }

    @Transactional(readOnly = true)
    public ChatSession getOwnedActiveSession(String username, Long sessionId) {
        return chatSessionRepository.findByIdAndUserUsernameAndDeletedAtIsNull(sessionId, username)
                .orElseThrow(() -> new CustomException("会话不存在或无权访问", HttpStatus.NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<Map<String, String>> getRecentHistory(String username, Long sessionId, int limit) {
        ChatSession session = getOwnedActiveSession(username, sessionId);
        int effectiveLimit = Math.min(Math.max(0, limit), ChatHistoryCache.MAX_MESSAGES);
        if (effectiveLimit == 0) {
            return List.of();
        }

        var cached = chatHistoryCache.getRecentHistory(session.getId(), effectiveLimit);
        if (cached.isPresent()) {
            return cached.get();
        }

        List<ChatMessage> latestMessages = chatMessageRepository.findTop20BySessionIdOrderByCreatedAtDesc(session.getId());
        List<Map<String, String>> history = latestMessages.stream()
                .filter(message -> "user".equals(message.getRole()) || "assistant".equals(message.getRole()))
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt))
                .map(message -> {
                    Map<String, String> item = new HashMap<>();
                    item.put("role", message.getRole());
                    item.put("content", message.getContent());
                    return item;
                })
                .toList();
        chatHistoryCache.putRecentHistory(session.getId(), history);
        int fromIndex = Math.max(0, history.size() - effectiveLimit);
        return List.copyOf(history.subList(fromIndex, history.size()));
    }

    @Transactional
    public boolean appendCompletedExchange(String username,
                                           Long sessionId,
                                           String userMessage,
                                           String assistantResponse,
                                           String generatedTitle) {
        return appendCompletedExchange(username, sessionId, userMessage, assistantResponse, generatedTitle, null);
    }

    @Transactional
    public boolean appendCompletedExchange(String username,
                                           Long sessionId,
                                           String userMessage,
                                           String assistantResponse,
                                           String generatedTitle,
                                           String agentTrace) {
        return appendCompletedExchange(username, sessionId, userMessage, assistantResponse, generatedTitle, agentTrace, null);
    }

    @Transactional
    public boolean appendCompletedExchange(String username,
                                           Long sessionId,
                                           String userMessage,
                                           String assistantResponse,
                                           String generatedTitle,
                                           String agentTrace,
                                           Long thinkingDurationMs) {
        return appendExchange(username, sessionId, userMessage, assistantResponse, generatedTitle,
                agentTrace, thinkingDurationMs, "finished");
    }

    @Transactional
    public boolean appendCancelledExchange(String username,
                                           Long sessionId,
                                           String userMessage,
                                           String assistantResponse,
                                           String agentTrace,
                                           Long thinkingDurationMs) {
        return appendExchange(username, sessionId, userMessage, assistantResponse, null,
                agentTrace, thinkingDurationMs, "cancelled");
    }

    private boolean appendExchange(String username,
                                   Long sessionId,
                                   String userMessage,
                                   String assistantResponse,
                                   String generatedTitle,
                                   String agentTrace,
                                   Long thinkingDurationMs,
                                   String assistantStatus) {
        ChatSession session = getOwnedActiveSession(username, sessionId);
        boolean wasEmpty = !chatMessageRepository.existsBySessionId(session.getId());
        chatMessageRepository.save(newMessage(session, "user", userMessage, "finished"));
        ChatMessage assistantMessage = newMessage(session, "assistant", assistantResponse, assistantStatus);
        assistantMessage.setAgentTrace(agentTrace);
        assistantMessage.setThinkingDurationMs(thinkingDurationMs);
        chatMessageRepository.save(assistantMessage);
        if (wasEmpty && !session.isTitleGenerated()) {
            String normalizedTitle = normalizeGeneratedTitleOnly(generatedTitle);
            if (normalizedTitle != null) {
                session.setTitle(normalizedTitle);
                session.setTitleGenerated(true);
            }
        }
        session.setUpdatedAt(LocalDateTime.now());
        chatSessionRepository.save(session);
        boolean seedCacheIfMissing = wasEmpty;
        afterCommit(() -> chatHistoryCache.appendExchange(
                sessionId, userMessage, assistantResponse, seedCacheIfMissing));
        return wasEmpty;
    }

    @Transactional
    public String ensureFallbackTitle(String username, Long sessionId, String userMessage) {
        String fallback = deriveFallbackTitle(userMessage);
        if (fallback == null) {
            return null;
        }
        getOwnedActiveSession(username, sessionId);
        return chatSessionRepository.setFallbackTitleIfDefault(sessionId, fallback) == 1
                ? fallback
                : null;
    }

    @Transactional
    public String updateGeneratedTitle(String username, Long sessionId, String generatedTitle) {
        String normalized = normalizeGeneratedTitleOnly(generatedTitle);
        if (normalized == null) {
            return null;
        }
        getOwnedActiveSession(username, sessionId);
        return chatSessionRepository.setGeneratedTitleIfPending(sessionId, normalized) == 1
                ? normalized
                : null;
    }

    private User getUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
    }

    private void applyScope(ChatSession session, ChatScopeService.ScopeSelection scope) {
        session.setScopeType(scope.type());
        session.setScopeValue(scope.value());
        session.setScopeLabel(scope.label());
        session.setScopeDetails(scope.details());
    }

    private void applyModel(ChatSession session, ModelConfigService.ResolvedModelConfig model) {
        session.setLlmConfigId(model.id());
        session.setLlmModelName(model.modelName());
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

    private String normalizeGeneratedTitleOnly(String generatedTitle) {
        String raw = generatedTitle == null ? "" : generatedTitle.trim();
        if (raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0) {
            return null;
        }
        String candidate = raw
                .replace("\"", "")
                .replace("'", "")
                .replace("“", "")
                .replace("”", "")
                .replaceFirst("^(?:标题|会话标题|Title)\\s*[:：]\\s*", "")
                .trim();
        int length = candidate.codePointCount(0, candidate.length());
        if (length < 2 || length > MAX_TITLE_LENGTH || candidate.matches(".*[。！？!?；;].*")) {
            return null;
        }
        return candidate;
    }

    private String deriveFallbackTitle(String userMessage) {
        String candidate = userMessage == null ? "" : userMessage.replaceAll("\\s+", " ").trim();
        if (candidate.isEmpty()) {
            return null;
        }
        int length = candidate.codePointCount(0, candidate.length());
        if (length <= MAX_FALLBACK_TITLE_LENGTH) {
            return candidate;
        }
        return candidate.substring(0, candidate.offsetByCodePoints(0, MAX_FALLBACK_TITLE_LENGTH));
    }

    private String abbreviate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }
}
