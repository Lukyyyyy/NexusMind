package com.luky.nexusmind.handler;

import com.luky.nexusmind.service.ChatHandler;
import com.luky.nexusmind.utils.JwtUtils;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ChatWebSocketHandlerTest {

    @Test
    void chatMessagesUseUsernameForSessionStateAndNumericUserIdForTracing() throws Exception {
        CapturingChatHandler chatHandler = new CapturingChatHandler();
        JwtUtils jwtUtils = new FixedJwtUtils("42", "admin");
        ChatWebSocketHandler handler = new ChatWebSocketHandler(chatHandler, jwtUtils, fixedUserRepository(null));
        WebSocketSession session = fixedSession();

        handler.handleTextMessage(session, new TextMessage("hello"));

        assertEquals("admin", chatHandler.lastUserId);
        assertEquals("42", chatHandler.lastTraceUserId);
        assertEquals("hello", chatHandler.lastMessage);
        assertSame(session, chatHandler.lastSession);
    }

    @Test
    void chatMessagesResolveUsernameFallbackOnlyForTracingUserId() throws Exception {
        CapturingChatHandler chatHandler = new CapturingChatHandler();
        JwtUtils jwtUtils = new FixedJwtUtils(null, "admin");
        User admin = new User();
        admin.setId(1L);
        admin.setUsername("admin");
        ChatWebSocketHandler handler = new ChatWebSocketHandler(chatHandler, jwtUtils, fixedUserRepository(admin));
        WebSocketSession session = fixedSession();

        handler.handleTextMessage(session, new TextMessage("hello"));

        assertEquals("admin", chatHandler.lastUserId);
        assertEquals("1", chatHandler.lastTraceUserId);
    }

    private static WebSocketSession fixedSession() {
        return (WebSocketSession) Proxy.newProxyInstance(
                WebSocketSession.class.getClassLoader(),
                new Class<?>[]{WebSocketSession.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUri" -> URI.create("ws://localhost/chat/jwt-token");
                    case "getId" -> "session-1";
                    case "isOpen" -> true;
                    default -> null;
                });
    }

    private static UserRepository fixedUserRepository(User user) {
        return (UserRepository) Proxy.newProxyInstance(
                UserRepository.class.getClassLoader(),
                new Class<?>[]{UserRepository.class},
                (proxy, method, args) -> {
                    if ("findByUsername".equals(method.getName())) {
                        return Optional.ofNullable(user)
                                .filter(item -> item.getUsername().equals(args[0]));
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static class CapturingChatHandler extends ChatHandler {
        private String lastUserId;
        private String lastTraceUserId;
        private String lastMessage;
        private WebSocketSession lastSession;

        private CapturingChatHandler() {
            super(null, null, null, null);
        }

        @Override
        public void processMessage(String userId, String userMessage, WebSocketSession session) {
            this.lastUserId = userId;
            this.lastMessage = userMessage;
            this.lastSession = session;
        }

        @Override
        public void processMessage(String userId, String userMessage, WebSocketSession session, String traceUserId) {
            this.lastUserId = userId;
            this.lastTraceUserId = traceUserId;
            this.lastMessage = userMessage;
            this.lastSession = session;
        }

        @Override
        public void processMessage(String userId,
                                   Long chatSessionId,
                                   String userMessage,
                                   WebSocketSession session,
                                   String traceUserId) {
            this.lastUserId = userId;
            this.lastTraceUserId = traceUserId;
            this.lastMessage = userMessage;
            this.lastSession = session;
        }
    }

    private static class FixedJwtUtils extends JwtUtils {
        private final String userId;
        private final String username;

        private FixedJwtUtils(String userId) {
            this(userId, null);
        }

        private FixedJwtUtils(String userId, String username) {
            this.userId = userId;
            this.username = username;
        }

        @Override
        public String extractUserIdFromToken(String token) {
            return userId;
        }

        @Override
        public String extractUsernameFromToken(String token) {
            return username;
        }
    }
}
