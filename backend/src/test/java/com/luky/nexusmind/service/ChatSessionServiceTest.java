package com.luky.nexusmind.service;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.model.ChatMessage;
import com.luky.nexusmind.model.ChatSession;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.ChatMessageRepository;
import com.luky.nexusmind.repository.ChatSessionRepository;
import com.luky.nexusmind.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ChatSessionServiceTest {

    private ChatSessionService service;
    private InMemoryUserRepository users;
    private InMemoryChatSessionRepository sessions;
    private InMemoryChatMessageRepository messages;
    private InMemoryChatHistoryCache historyCache;

    @BeforeEach
    void setUp() {
        users = new InMemoryUserRepository();
        messages = new InMemoryChatMessageRepository();
        sessions = new InMemoryChatSessionRepository(messages);
        historyCache = new InMemoryChatHistoryCache();
        service = new ChatSessionService(users.proxy(), sessions.proxy(), messages.proxy(), historyCache);
    }

    @Test
    void listSessionsIncludesOnlyOwnedSessionsWithMessages() {
        User alice = user("alice", 1L);
        User bob = user("bob", 2L);
        users.save(alice);
        users.save(bob);

        ChatSession aliceSession = service.createSession("alice");
        ChatSession emptyAliceSession = service.createSession("alice");
        ChatSession bobSession = service.createSession("bob");
        service.appendCompletedExchange("alice", aliceSession.getId(), "问题", "回答", null);
        service.appendCompletedExchange("bob", bobSession.getId(), "问题", "回答", null);

        List<ChatSession> result = service.listSessions("alice");

        assertEquals("新会话", emptyAliceSession.getTitle());
        assertFalse(emptyAliceSession.isTitleGenerated());
        assertEquals(List.of(aliceSession.getId()), result.stream().map(ChatSession::getId).toList());
    }

    @Test
    void softDeletedSessionsAreHiddenAndCannotBeLoaded() {
        users.save(user("alice", 1L));
        ChatSession session = service.createSession("alice");

        service.deleteSession("alice", session.getId());

        assertTrue(service.listSessions("alice").isEmpty());
        assertNotNull(sessions.findRaw(session.getId()).orElseThrow().getDeletedAt());
        assertThrows(CustomException.class, () -> service.getMessages("alice", session.getId()));
    }

    @Test
    void renameSessionRejectsBlankTitleAndUpdatesOwnedSession() {
        users.save(user("alice", 1L));
        ChatSession session = service.createSession("alice");

        ChatSession renamed = service.renameSession("alice", session.getId(), "  项目问答  ");

        assertEquals("项目问答", renamed.getTitle());
        assertTrue(renamed.isTitleGenerated());
        assertThrows(CustomException.class, () -> service.renameSession("alice", session.getId(), " "));
    }

    @Test
    void scopeCannotChangeAfterTheFirstExchange() {
        users.save(user("alice", 1L));
        ChatSession session = service.createSession("alice");
        ChatScopeService.ScopeSelection privateScope = new ChatScopeService.ScopeSelection(
                com.luky.nexusmind.model.ChatScopeType.PRIVATE, null, "我的私人空间", null);

        service.updateScope("alice", session.getId(), privateScope);
        assertEquals("我的私人空间", session.getScopeLabel());
        service.appendCompletedExchange("alice", session.getId(), "问题", "回答", null);

        assertThrows(CustomException.class, () -> service.updateScope("alice", session.getId(),
                new ChatScopeService.ScopeSelection(
                        com.luky.nexusmind.model.ChatScopeType.ALL, null, "全部知识", null)));
    }

    @Test
    void appendCompletedExchangeKeepsNewSessionTitleWhileGeneratedTitleIsPending() {
        users.save(user("alice", 1L));
        ChatSession session = service.createSession("alice");

        boolean firstExchange = service.appendCompletedExchange(
                "alice",
                session.getId(),
                "请帮我总结这份产品需求文档中的核心风险",
                "核心风险包括范围不清和验收标准缺失。",
                null
        );

        List<ChatMessage> stored = service.getMessages("alice", session.getId());
        assertEquals(List.of("user", "assistant"), stored.stream().map(ChatMessage::getRole).toList());
        assertEquals("finished", stored.get(1).getStatus());
        assertEquals("新会话", session.getTitle());
        assertFalse(session.isTitleGenerated());
        assertTrue(firstExchange);
    }

    @Test
    void appendCancelledExchangePersistsPartialResponse() {
        users.save(user("alice", 1L));
        ChatSession session = service.createSession("alice");

        service.appendCancelledExchange("alice", session.getId(), "问题", "部分回答", null, 1234L);

        List<ChatMessage> stored = service.getMessages("alice", session.getId());
        assertEquals("finished", stored.get(0).getStatus());
        assertEquals("部分回答", stored.get(1).getContent());
        assertEquals("cancelled", stored.get(1).getStatus());
        assertEquals(1234L, stored.get(1).getThinkingDurationMs());
    }

    @Test
    void fallbackTitleRemainsEligibleForLaterGeneratedTitle() {
        users.save(user("alice", 1L));
        ChatSession session = service.createSession("alice");
        assertTrue(service.appendCompletedExchange("alice", session.getId(), "首次问题", "首次回答", null));

        boolean firstExchange = service.appendCompletedExchange(
                "alice", session.getId(), "后续问题", "后续回答", null);

        assertFalse(firstExchange);
        assertFalse(session.isTitleGenerated());
        assertEquals("新会话", session.getTitle());

        service.updateGeneratedTitle("alice", session.getId(), "技术风险总结");

        assertFalse(service.appendCompletedExchange(
                "alice", session.getId(), "第三个问题", "第三个回答", null));
    }

    @Test
    void fallbackTitleIsImmediateStableAndStillReplaceableByAi() {
        users.save(user("alice", 1L));
        ChatSession session = service.createSession("alice");

        assertEquals("请帮我总结这份产品需求文档中的核心风险和改进建议", service.ensureFallbackTitle(
                "alice", session.getId(), "  请帮我总结这份产品需求文档中的核心风险和改进建议  "));
        assertNull(service.ensureFallbackTitle("alice", session.getId(), "第二条消息"));
        assertFalse(session.isTitleGenerated());

        assertEquals("产品需求风险与改进建议", service.updateGeneratedTitle(
                "alice", session.getId(), "“产品需求风险与改进建议”"));
        assertEquals("产品需求风险与改进建议", session.getTitle());
        assertTrue(session.isTitleGenerated());
    }

    @Test
    void generatedTitleCannotOverwriteManualRename() {
        users.save(user("alice", 1L));
        ChatSession session = service.createSession("alice");
        service.ensureFallbackTitle("alice", session.getId(), "原始问题");
        service.renameSession("alice", session.getId(), "用户指定标题");

        assertNull(service.updateGeneratedTitle("alice", session.getId(), "模型晚到标题"));
        assertEquals("用户指定标题", session.getTitle());
    }

    @Test
    void generatedTitleRejectsAnswerShapedOrMultilineOutput() {
        users.save(user("alice", 1L));
        ChatSession session = service.createSession("alice");

        assertNull(service.updateGeneratedTitle("alice", session.getId(), "第一行\n第二行"));
        assertNull(service.updateGeneratedTitle("alice", session.getId(), "这是问题的答案。"));
        assertEquals("新会话", session.getTitle());
    }

    @Test
    void completedExchangeDoesNotOverwriteTitleGeneratedInParallel() {
        users.save(user("alice", 1L));
        ChatSession session = service.createSession("alice");
        service.updateGeneratedTitle("alice", session.getId(), "产品风险分析");

        service.appendCompletedExchange("alice", session.getId(), "原始问题", "完整回答", null);

        assertEquals("产品风险分析", session.getTitle());
        assertTrue(session.isTitleGenerated());
    }

    @Test
    void appendCompletedExchangePersistsAgentTraceOnAssistantMessage() {
        users.save(user("alice", 1L));
        ChatSession session = service.createSession("alice");
        String trace = "[{\"stepId\":\"thinking\",\"title\":\"正在分析问题\"}]";

        service.appendCompletedExchange("alice", session.getId(), "问题", "回答", null, trace);

        List<ChatMessage> stored = service.getMessages("alice", session.getId());
        assertNull(stored.get(0).getAgentTrace());
        assertEquals(trace, stored.get(1).getAgentTrace());
    }

    @Test
    void appendCompletedExchangePersistsThinkingDurationOnAssistantMessage() {
        users.save(user("alice", 1L));
        ChatSession session = service.createSession("alice");

        service.appendCompletedExchange("alice", session.getId(), "问题", "回答", null, null, 1234L);

        List<ChatMessage> stored = service.getMessages("alice", session.getId());
        assertNull(stored.get(0).getThinkingDurationMs());
        assertEquals(1234L, stored.get(1).getThinkingDurationMs());
    }

    @Test
    void updateGeneratedTitleIgnoresBlankTitleAndUpdatesOwnedSession() {
        users.save(user("alice", 1L));
        ChatSession session = service.createSession("alice");
        service.appendCompletedExchange("alice", session.getId(), "原始问题", "回答", null);

        service.updateGeneratedTitle("alice", session.getId(), "  技术风险总结  ");

        assertEquals("技术风险总结", session.getTitle());
        service.updateGeneratedTitle("alice", session.getId(), " ");
        assertEquals("技术风险总结", session.getTitle());
    }

    @Test
    void generatedTitlesDoNotChangeConversationActivityTime() {
        users.save(user("alice", 1L));
        ChatSession session = service.createSession("alice");
        LocalDateTime activityTime = LocalDateTime.of(2026, 8, 25, 17, 11);
        session.setUpdatedAt(activityTime);

        service.ensureFallbackTitle("alice", session.getId(), "总结知识库");
        service.updateGeneratedTitle("alice", session.getId(), "知识库内容总结");

        assertEquals(activityTime, session.getUpdatedAt());
    }

    @Test
    void historyReturnsLatestTwentyMessagesInChronologicalOrder() {
        users.save(user("alice", 1L));
        ChatSession session = service.createSession("alice");
        for (int i = 0; i < 12; i++) {
            service.appendCompletedExchange("alice", session.getId(), "q" + i, "a" + i, "标题");
        }

        List<Map<String, String>> history = service.getRecentHistory("alice", session.getId(), 20);

        assertEquals(20, history.size());
        assertEquals("user", history.get(0).get("role"));
        assertEquals("q2", history.get(0).get("content"));
        assertEquals("assistant", history.get(19).get("role"));
        assertEquals("a11", history.get(19).get("content"));
    }

    @Test
    void historyCacheMissLoadsMysqlOnceAndThenServesTheCache() {
        users.save(user("alice", 1L));
        ChatSession session = service.createSession("alice");
        service.appendCompletedExchange("alice", session.getId(), "问题", "回答", null);
        historyCache.evict(session.getId());

        List<Map<String, String>> first = service.getRecentHistory("alice", session.getId(), 20);
        List<Map<String, String>> second = service.getRecentHistory("alice", session.getId(), 20);

        assertEquals(first, second);
        assertEquals(1, messages.latestQueries);
        assertEquals(1, historyCache.puts);
    }

    @Test
    void completedExchangeUpdatesCacheWithoutAnotherMysqlRead() {
        users.save(user("alice", 1L));
        ChatSession session = service.createSession("alice");

        service.appendCompletedExchange("alice", session.getId(), "问题", "回答", null);
        List<Map<String, String>> history = service.getRecentHistory("alice", session.getId(), 20);

        assertEquals(List.of("问题", "回答"), history.stream().map(item -> item.get("content")).toList());
        assertEquals(0, messages.latestQueries);
    }

    @Test
    void deletingSessionEvictsItsHistoryCache() {
        users.save(user("alice", 1L));
        ChatSession session = service.createSession("alice");
        service.appendCompletedExchange("alice", session.getId(), "问题", "回答", null);
        assertTrue(historyCache.values.containsKey(session.getId()));

        service.deleteSession("alice", session.getId());

        assertFalse(historyCache.values.containsKey(session.getId()));
    }

    private static User user(String username, Long id) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword("secret");
        user.setRole(User.Role.USER);
        return user;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type.isPrimitive()) {
            return 0;
        }
        return null;
    }

    private static class InMemoryUserRepository {
        private final Map<String, User> byUsername = new HashMap<>();

        UserRepository proxy() {
            return ChatSessionServiceTest.proxy(UserRepository.class, (proxy, method, args) -> switch (method.getName()) {
                case "findByUsername" -> Optional.ofNullable(byUsername.get((String) args[0]));
                case "save" -> save((User) args[0]);
                default -> defaultValue(method.getReturnType());
            });
        }

        User save(User user) {
            byUsername.put(user.getUsername(), user);
            return user;
        }
    }

    private static class InMemoryChatSessionRepository {
        private final Map<Long, ChatSession> byId = new HashMap<>();
        private final InMemoryChatMessageRepository messages;
        private long nextId = 1L;

        InMemoryChatSessionRepository(InMemoryChatMessageRepository messages) {
            this.messages = messages;
        }

        ChatSessionRepository proxy() {
            return ChatSessionServiceTest.proxy(ChatSessionRepository.class, (proxy, method, args) -> switch (method.getName()) {
                case "save" -> save((ChatSession) args[0]);
                case "findHistoryByUsername" -> list((String) args[0]);
                case "findByIdAndUserUsernameAndDeletedAtIsNull" -> findActive((Long) args[0], (String) args[1]);
                case "setFallbackTitleIfDefault" -> setFallbackTitleIfDefault(
                        (Long) args[0], (String) args[1]);
                case "setGeneratedTitleIfPending" -> setGeneratedTitleIfPending(
                        (Long) args[0], (String) args[1]);
                default -> defaultValue(method.getReturnType());
            });
        }

        ChatSession save(ChatSession session) {
            if (session.getId() == null) {
                session.setId(nextId++);
            }
            LocalDateTime now = LocalDateTime.now();
            if (session.getCreatedAt() == null) {
                session.setCreatedAt(now);
            }
            session.setUpdatedAt(now);
            byId.put(session.getId(), session);
            return session;
        }

        List<ChatSession> list(String username) {
            return byId.values().stream()
                    .filter(session -> username.equals(session.getUser().getUsername()))
                    .filter(session -> session.getDeletedAt() == null)
                    .filter(session -> messages.exists(session.getId()))
                    .sorted(Comparator.comparing(ChatSession::getUpdatedAt).reversed())
                    .toList();
        }

        Optional<ChatSession> findActive(Long id, String username) {
            return byId.values().stream()
                    .filter(session -> session.getId().equals(id))
                    .filter(session -> username.equals(session.getUser().getUsername()))
                    .filter(session -> session.getDeletedAt() == null)
                    .findFirst();
        }

        Optional<ChatSession> findRaw(Long id) {
            return Optional.ofNullable(byId.get(id));
        }

        int setFallbackTitleIfDefault(Long id, String title) {
            Optional<ChatSession> session = findRaw(id)
                    .filter(item -> item.getDeletedAt() == null)
                    .filter(item -> !item.isTitleGenerated())
                    .filter(item -> "新会话".equals(item.getTitle()));
            session.ifPresent(item -> saveTitle(item, title, false));
            return session.isPresent() ? 1 : 0;
        }

        int setGeneratedTitleIfPending(Long id, String title) {
            Optional<ChatSession> session = findRaw(id)
                    .filter(item -> item.getDeletedAt() == null)
                    .filter(item -> !item.isTitleGenerated());
            session.ifPresent(item -> saveTitle(item, title, true));
            return session.isPresent() ? 1 : 0;
        }

        void saveTitle(ChatSession session, String title, boolean generated) {
            session.setTitle(title);
            session.setTitleGenerated(generated);
        }
    }

    private static class InMemoryChatMessageRepository {
        private final List<ChatMessage> rows = new ArrayList<>();
        private long nextId = 1L;
        private int latestQueries;

        ChatMessageRepository proxy() {
            return ChatSessionServiceTest.proxy(ChatMessageRepository.class, (proxy, method, args) -> switch (method.getName()) {
                case "save" -> save((ChatMessage) args[0]);
                case "findBySessionIdOrderByCreatedAtAsc" -> findAll((Long) args[0]);
                case "findTop20BySessionIdOrderByCreatedAtDesc" -> findLatest20((Long) args[0]);
                case "existsBySessionId" -> rows.stream().anyMatch(message -> message.getSession().getId().equals((Long) args[0]));
                case "existsBySessionIdAndRoleIn" -> rows.stream().anyMatch(message ->
                        message.getSession().getId().equals((Long) args[0])
                                && ((List<?>) args[1]).contains(message.getRole()));
                default -> defaultValue(method.getReturnType());
            });
        }

        ChatMessage save(ChatMessage message) {
            if (message.getId() == null) {
                message.setId(nextId++);
            }
            if (message.getCreatedAt() == null) {
                message.setCreatedAt(LocalDateTime.now().plusNanos(message.getId()));
            }
            rows.add(message);
            return message;
        }

        List<ChatMessage> findAll(Long sessionId) {
            return rows.stream()
                    .filter(message -> message.getSession().getId().equals(sessionId))
                    .sorted(Comparator.comparing(ChatMessage::getCreatedAt))
                    .toList();
        }

        List<ChatMessage> findLatest20(Long sessionId) {
            latestQueries++;
            return rows.stream()
                    .filter(message -> message.getSession().getId().equals(sessionId))
                    .sorted(Comparator.comparing(ChatMessage::getCreatedAt).reversed())
                    .limit(20)
                    .toList();
        }

        boolean exists(Long sessionId) {
            return rows.stream().anyMatch(message -> message.getSession().getId().equals(sessionId));
        }
    }

    private static class InMemoryChatHistoryCache implements ChatHistoryCache {
        private final Map<Long, List<Map<String, String>>> values = new HashMap<>();
        private int puts;

        @Override
        public Optional<List<Map<String, String>>> getRecentHistory(Long sessionId, int limit) {
            List<Map<String, String>> history = values.get(sessionId);
            if (history == null) {
                return Optional.empty();
            }
            int fromIndex = Math.max(0, history.size() - Math.min(limit, MAX_MESSAGES));
            return Optional.of(List.copyOf(history.subList(fromIndex, history.size())));
        }

        @Override
        public void putRecentHistory(Long sessionId, List<Map<String, String>> history) {
            puts++;
            values.put(sessionId, tail(history));
        }

        @Override
        public void appendExchange(Long sessionId,
                                   String userMessage,
                                   String assistantResponse,
                                   boolean seedIfMissing) {
            List<Map<String, String>> existing = values.get(sessionId);
            if (existing == null && !seedIfMissing) {
                return;
            }
            List<Map<String, String>> updated = new ArrayList<>(existing == null ? List.of() : existing);
            updated.add(Map.of("role", "user", "content", userMessage));
            updated.add(Map.of("role", "assistant", "content", assistantResponse));
            values.put(sessionId, tail(updated));
        }

        @Override
        public void evict(Long sessionId) {
            values.remove(sessionId);
        }

        private List<Map<String, String>> tail(List<Map<String, String>> history) {
            int fromIndex = Math.max(0, history.size() - MAX_MESSAGES);
            return List.copyOf(history.subList(fromIndex, history.size()));
        }
    }
}
