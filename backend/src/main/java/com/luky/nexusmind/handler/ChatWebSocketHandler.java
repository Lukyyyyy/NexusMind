package com.luky.nexusmind.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luky.nexusmind.repository.UserRepository;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.service.ChatHandler;
import com.luky.nexusmind.service.ChatWebSocketTicketService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private final ChatHandler chatHandler;
    private final ConcurrentHashMap<String, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatWebSocketTicketService chatTickets;
    private final UserRepository userRepository;

    public ChatWebSocketHandler(ChatHandler chatHandler, ChatWebSocketTicketService chatTickets, UserRepository userRepository) {
        this.chatHandler = chatHandler;
        this.chatTickets = chatTickets;
        this.userRepository = userRepository;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String ticket = lastPathSegment(session);
        String username = chatTickets.consume(ticket);
        User user = username == null ? null : userRepository.findByUsername(username).orElse(null);
        if (username == null || user == null || !user.isEnabled()) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        session.getAttributes().put("chatUsername", username);
        session.getAttributes().put("chatUserId", String.valueOf(user.getId()));
        sessions.computeIfAbsent(username, ignored -> ConcurrentHashMap.newKeySet()).add(session);
        logger.info("WebSocket连接已建立，用户ID: {}，会话ID: {}", username, session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            UserIdentity identity = extractUserIdentity(session);
            if (userRepository.findByUsername(identity.chatUserId()).filter(User::isEnabled).isEmpty()) {
                disconnectUser(identity.chatUserId());
                return;
            }
            String payload = message.getPayload();
            logger.info("接收到消息，用户ID: {}，会话ID: {}，消息长度: {}", 
                       identity.chatUserId(), session.getId(), payload.length());
            
            // 检查是否是JSON格式的系统指令
            if (payload.trim().startsWith("{")) {
                try {
                    Map<String, Object> jsonMessage = objectMapper.readValue(payload, Map.class);
                    String messageType = (String) jsonMessage.get("type");
                    String internalToken = (String) jsonMessage.get("_internal_cmd_token");
                    
                    // 停止指令绑定已认证会话，不再依赖全局内部令牌。
                    if ("stop".equals(messageType) && (internalToken == null || internalToken.isBlank())) {
                        // 处理停止指令
                        logger.info("收到有效的停止按钮指令，用户ID: {}，会话ID: {}", identity.chatUserId(), session.getId());
                        chatHandler.stopResponse(identity.chatUserId(), session);
                        return;
                    }
                    if ("message".equals(messageType)) {
                        Object sessionIdValue = jsonMessage.get("sessionId");
                        String content = (String) jsonMessage.get("content");
                        if (sessionIdValue == null || content == null || content.trim().isEmpty()) {
                            sendErrorMessage(session, "消息内容或会话ID不能为空");
                            return;
                        }
                        Long chatSessionId = Long.valueOf(String.valueOf(sessionIdValue));
                        chatHandler.processMessage(identity.chatUserId(), chatSessionId, content, session, identity.traceUserId());
                        return;
                    }
                    
                    // 其他JSON消息当作普通消息处理
                    logger.debug("收到JSON格式的聊天消息，当作普通消息处理");
                } catch (Exception jsonParseError) {
                    // JSON解析失败，当作普通文本消息处理
                    logger.debug("JSON解析失败，当作普通消息处理: {}", jsonParseError.getMessage());
                }
            }
            
            // 普通聊天消息处理（保持向下兼容）
            chatHandler.processMessage(identity.chatUserId(), payload, session, identity.traceUserId());
            
        } catch (Exception e) {
            logger.error("处理消息出错，会话ID: {}，错误: {}", session.getId(), e.getMessage(), e);
            sendErrorMessage(session, "消息处理失败");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        try {
            UserIdentity identity = extractUserIdentity(session);
            Set<WebSocketSession> values = sessions.get(identity.chatUserId());
            if (values != null) {
                values.remove(session);
                if (values.isEmpty()) sessions.remove(identity.chatUserId());
            }
            logger.info("WebSocket连接已关闭，用户ID: {}，会话ID: {}，状态: {}",
                    identity.chatUserId(), session.getId(), status);
        } catch (IllegalStateException e) {
            logger.debug("未认证会话关闭: {}", session.getId());
        }
    }

    private UserIdentity extractUserIdentity(WebSocketSession session) {
        Object storedUsername = session.getAttributes().get("chatUsername");
        Object storedUserId = session.getAttributes().get("chatUserId");
        if (storedUsername instanceof String username && !username.isBlank()) {
            String userId = storedUserId instanceof String id && !id.isBlank() ? id : username;
            return new UserIdentity(username, userId);
        }
        throw new IllegalStateException("会话缺少已认证身份");
    }

    public void disconnectUser(String username) {
        Set<WebSocketSession> userSessions = sessions.remove(username);
        if (userSessions == null) return;
        for (WebSocketSession session : userSessions) {
            chatHandler.stopResponse(username, session);
            try {
                if (session.isOpen()) session.close(CloseStatus.POLICY_VIOLATION);
            } catch (Exception e) {
                logger.debug("关闭已禁用用户的聊天会话失败: {}", e.getMessage());
            }
        }
    }

    private String lastPathSegment(WebSocketSession session) {
        String path = session.getUri() == null ? "" : session.getUri().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private record UserIdentity(String chatUserId, String traceUserId) {
    }

    private void sendErrorMessage(WebSocketSession session, String errorMessage) {
        try {
            Map<String, String> error = Map.of("error", errorMessage);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(error)));
            logger.info("已发送错误消息到会话: {}, 错误: {}", session.getId(), errorMessage);
        } catch (Exception e) {
            logger.error("发送错误消息失败: {}", e.getMessage(), e);
        }
    }
    
}
