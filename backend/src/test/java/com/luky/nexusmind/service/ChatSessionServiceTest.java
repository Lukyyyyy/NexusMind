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

    @BeforeEach
    void setUp() {
        users = new InMemoryUserRepository();
        sessions = new InMemoryChatSessionRepository();
        messages = new InMemoryChatMessageRepository();
        service = new ChatSessionService(users.proxy(), sessions.proxy(), messages.proxy());
    }

    @Test
    void createSessionReturnsDefaultTitleAndListsOnlyOwnerSessions() {
        User alice = user("alice", 1L);
        User bob = user("bob", 2L);
        users.save(alice);
        users.save(bob);

        ChatSession aliceSession = service.createSession("alice");
        service.createSession("bob");

        List<ChatSession> result = service.listSessions("alice");

        assertEquals("新会话", aliceSession.getTitle());
        assertFalse(aliceSession.isTitleGenerated());
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
    void appendCompletedExchangeStoresMessagesAndFallbackTitleForFirstExchange() {
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
        assertEquals("请帮我总结这份产品需求文档中的核心风险", session.getTitle());
        assertTrue(session.isTitleGenerated());
        assertTrue(firstExchange);
    }

    @Test
    void appendCompletedExchangeReturnsFalseAfterFirstExchangeAndKeepsExistingTitle() {
        users.save(user("alice", 1L));
        ChatSession session = service.createSession("alice");
        assertTrue(service.appendCompletedExchange("alice", session.getId(), "首次问题", "首次回答", null));

        boolean firstExchange = service.appendCompletedExchange("alice", session.getId(), "后续问题", "后续回答", "新标题");

        assertFalse(firstExchange);
        assertEquals("首次问题", session.getTitle());
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
        private long nextId = 1L;

        ChatSessionRepository proxy() {
            return ChatSessionServiceTest.proxy(ChatSessionRepository.class, (proxy, method, args) -> switch (method.getName()) {
                case "save" -> save((ChatSession) args[0]);
                case "findByUserUsernameAndDeletedAtIsNullOrderByUpdatedAtDesc" -> list((String) args[0]);
                case "findByIdAndUserUsernameAndDeletedAtIsNull" -> findActive((Long) args[0], (String) args[1]);
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
    }

    private static class InMemoryChatMessageRepository {
        private final List<ChatMessage> rows = new ArrayList<>();
        private long nextId = 1L;

        ChatMessageRepository proxy() {
            return ChatSessionServiceTest.proxy(ChatMessageRepository.class, (proxy, method, args) -> switch (method.getName()) {
                case "save" -> save((ChatMessage) args[0]);
                case "findBySessionIdOrderByCreatedAtAsc" -> findAll((Long) args[0]);
                case "findTop20BySessionIdOrderByCreatedAtDesc" -> findLatest20((Long) args[0]);
                case "existsBySessionId" -> rows.stream().anyMatch(message -> message.getSession().getId().equals((Long) args[0]));
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
            return rows.stream()
                    .filter(message -> message.getSession().getId().equals(sessionId))
                    .sorted(Comparator.comparing(ChatMessage::getCreatedAt).reversed())
                    .limit(20)
                    .toList();
        }
    }
}
