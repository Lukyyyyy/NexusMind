package com.luky.nexusmind.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luky.nexusmind.repository.UserRepository;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class NotificationWebSocketHandler extends TextWebSocketHandler implements MessageListener {
    private static final String TICKET_PREFIX = "notification:ticket:";
    private final StringRedisTemplate redis;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final ChatWebSocketHandler chatWebSocketHandler;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<Long, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    public NotificationWebSocketHandler(StringRedisTemplate redis, UserRepository userRepository, ObjectMapper objectMapper,
                                        ChatWebSocketHandler chatWebSocketHandler) {
        this.redis = redis;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.chatWebSocketHandler = chatWebSocketHandler;
    }

    public String issueTicket(String username) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(TICKET_PREFIX + ticket, username, Duration.ofSeconds(60));
        return ticket;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String ticket = lastPathSegment(session);
        String username = redis.opsForValue().getAndDelete(TICKET_PREFIX + ticket);
        Long userId = username == null ? null : userRepository.findByUsername(username)
                .filter(com.luky.nexusmind.model.User::isEnabled).map(user -> user.getId()).orElse(null);
        if (userId == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        session.getAttributes().put("notificationUserId", userId);
        sessions.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object userId = session.getAttributes().get("notificationUserId");
        if (userId instanceof Long id) {
            Set<WebSocketSession> values = sessions.get(id);
            if (values != null) {
                values.remove(session);
                if (values.isEmpty()) sessions.remove(id);
            }
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String payload = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            JsonNode json = objectMapper.readTree(payload);
            long userId = json.path("userId").asLong();
            Set<WebSocketSession> targets = sessions.get(userId);
            TextMessage outbound = new TextMessage(payload);
            if (targets != null) {
                for (WebSocketSession session : targets) {
                    if (session.isOpen()) synchronized (session) { session.sendMessage(outbound); }
                }
            }
            if ("ACCOUNT_DISABLED".equals(json.path("data").path("type").asText())) {
                userRepository.findById(userId).ifPresent(user -> chatWebSocketHandler.disconnectUser(user.getUsername()));
                disconnectUser(userId);
            }
        } catch (Exception ignored) {
            // Durable notification history repairs any missed real-time event.
        }
    }

    public void disconnectUser(Long userId) {
        Set<WebSocketSession> targets = sessions.remove(userId);
        if (targets == null) return;
        for (WebSocketSession session : targets) {
            try {
                if (session.isOpen()) session.close(CloseStatus.POLICY_VIOLATION);
            } catch (Exception ignored) {
                // Session cleanup is best effort after the account has already been disabled.
            }
        }
    }

    public void notifyDisabledAndDisconnect(Long userId) {
        Set<WebSocketSession> targets = sessions.get(userId);
        if (targets != null) {
            try {
                TextMessage message = new TextMessage(objectMapper.writeValueAsString(java.util.Map.of(
                        "event", "account_disabled",
                        "data", java.util.Map.of("content", "你的账户已被禁用，请联系超级管理员"))));
                for (WebSocketSession session : targets) {
                    if (session.isOpen()) synchronized (session) { session.sendMessage(message); }
                }
            } catch (Exception ignored) {
                // The next authenticated request still returns ACCOUNT_DISABLED.
            }
        }
        disconnectUser(userId);
    }

    private String lastPathSegment(WebSocketSession session) {
        String path = session.getUri() == null ? "" : session.getUri().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
