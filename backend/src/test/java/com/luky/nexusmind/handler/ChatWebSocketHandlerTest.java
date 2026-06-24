package com.luky.nexusmind.handler;

import com.luky.nexusmind.service.ChatHandler;
import com.luky.nexusmind.utils.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Proxy;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ChatWebSocketHandlerTest {

    @Test
    void chatMessagesUseJwtUserIdForTracingAndSessionState() throws Exception {
        CapturingChatHandler chatHandler = new CapturingChatHandler();
        JwtUtils jwtUtils = new FixedJwtUtils("42");
        ChatWebSocketHandler handler = new ChatWebSocketHandler(chatHandler, jwtUtils);
        WebSocketSession session = fixedSession();

        handler.handleTextMessage(session, new TextMessage("hello"));

        assertEquals("42", chatHandler.lastUserId);
        assertEquals("hello", chatHandler.lastMessage);
        assertSame(session, chatHandler.lastSession);
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

    private static class CapturingChatHandler extends ChatHandler {
        private String lastUserId;
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
    }

    private static class FixedJwtUtils extends JwtUtils {
        private final String userId;

        private FixedJwtUtils(String userId) {
            this.userId = userId;
        }

        @Override
        public String extractUserIdFromToken(String token) {
            return userId;
        }
    }
}
