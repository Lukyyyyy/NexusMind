package com.luky.nexusmind.handler;

import com.luky.nexusmind.service.ChatHandler;
import com.luky.nexusmind.service.ChatWebSocketTicketService;
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
        User admin = new User();
        admin.setUsername("admin");
        ChatWebSocketHandler handler = new ChatWebSocketHandler(chatHandler, fixedTicketService(), fixedUserRepository(admin));
        WebSocketSession session = fixedSession("admin", "42");

        handler.handleTextMessage(session, new TextMessage("hello"));

        assertEquals("admin", chatHandler.lastUserId);
        assertEquals("42", chatHandler.lastTraceUserId);
        assertEquals("hello", chatHandler.lastMessage);
        assertSame(session, chatHandler.lastSession);
    }

    @Test
    void chatMessagesResolveUsernameFallbackOnlyForTracingUserId() throws Exception {
        CapturingChatHandler chatHandler = new CapturingChatHandler();
        User admin = new User();
        admin.setId(1L);
        admin.setUsername("admin");
        ChatWebSocketHandler handler = new ChatWebSocketHandler(chatHandler, fixedTicketService(), fixedUserRepository(admin));
        WebSocketSession session = fixedSession("admin", "1");

        handler.handleTextMessage(session, new TextMessage("hello"));

        assertEquals("admin", chatHandler.lastUserId);
        assertEquals("1", chatHandler.lastTraceUserId);
    }

    private static ChatWebSocketTicketService fixedTicketService() {
        return new ChatWebSocketTicketService(null) {
            @Override public String issue(String username) { return "ticket"; }
            @Override public String consume(String ticket) { return null; }
        };
    }

    private static WebSocketSession fixedSession(String username, String userId) {
        java.util.Map<String, Object> attributes = new java.util.HashMap<>();
        attributes.put("chatUsername", username);
        attributes.put("chatUserId", userId);
        return (WebSocketSession) Proxy.newProxyInstance(
                WebSocketSession.class.getClassLoader(),
                new Class<?>[]{WebSocketSession.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUri" -> URI.create("ws://localhost/chat/ticket");
                    case "getAttributes" -> attributes;
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
}
