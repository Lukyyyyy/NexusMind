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
import com.luky.nexusmind.service.ChatHandler;
import com.luky.nexusmind.utils.JwtUtils;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private final ChatHandler chatHandler;
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JwtUtils jwtUtils;
    private final UserRepository userRepository;
    
    // 内部指令令牌 - 可以从配置文件读取
    private static final String INTERNAL_CMD_TOKEN = "WSS_STOP_CMD_" + System.currentTimeMillis() % 1000000;

    public ChatWebSocketHandler(ChatHandler chatHandler, JwtUtils jwtUtils, UserRepository userRepository) {
        this.chatHandler = chatHandler;
        this.jwtUtils = jwtUtils;
        this.userRepository = userRepository;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        UserIdentity identity = extractUserIdentity(session);
        sessions.put(identity.chatUserId(), session);
        logger.info("WebSocket连接已建立，用户ID: {}，会话ID: {}，URI路径: {}", 
                    identity.chatUserId(), session.getId(), session.getUri().getPath());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        UserIdentity identity = extractUserIdentity(session);
        try {
            String payload = message.getPayload();
            logger.info("接收到消息，用户ID: {}，会话ID: {}，消息长度: {}", 
                       identity.chatUserId(), session.getId(), payload.length());
            
            // 检查是否是JSON格式的系统指令
            if (payload.trim().startsWith("{")) {
                try {
                    Map<String, Object> jsonMessage = objectMapper.readValue(payload, Map.class);
                    String messageType = (String) jsonMessage.get("type");
                    String internalToken = (String) jsonMessage.get("_internal_cmd_token");
                    
                    // 只有包含正确内部令牌的停止指令才处理
                    if ("stop".equals(messageType) && INTERNAL_CMD_TOKEN.equals(internalToken)) {
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
            logger.error("处理消息出错，用户ID: {}，会话ID: {}，错误: {}", 
                        identity.chatUserId(), session.getId(), e.getMessage(), e);
            sendErrorMessage(session, "消息处理失败：" + e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UserIdentity identity = extractUserIdentity(session);
        sessions.remove(identity.chatUserId());
        logger.info("WebSocket连接已关闭，用户ID: {}，会话ID: {}，状态: {}", 
                    identity.chatUserId(), session.getId(), status);
    }

    private UserIdentity extractUserIdentity(WebSocketSession session) {
        String path = session.getUri().getPath();
        String[] segments = path.split("/");
        String jwtToken = segments[segments.length - 1];

        String username = jwtUtils.extractUsernameFromToken(jwtToken);
        String userId = jwtUtils.extractUserIdFromToken(jwtToken);
        String chatUserId = hasText(username) ? username : userId;
        if (!hasText(chatUserId)) {
            logger.warn("无法从JWT令牌中提取用户信息，使用令牌作为用户ID: {}", jwtToken);
            chatUserId = jwtToken;
        }

        if (userId != null && !userId.isBlank()) {
            logger.debug("从JWT令牌中提取的用户ID: {}", userId);
            return new UserIdentity(chatUserId, userId);
        }

        if (!hasText(username)) {
            return new UserIdentity(chatUserId, chatUserId);
        }

        String traceUserId = userRepository.findByUsername(username)
                .map(user -> {
                    String numericUserId = String.valueOf(user.getId());
                    logger.debug("JWT令牌缺少用户ID，已通过用户名解析数字用户ID: username={}, userId={}", username, numericUserId);
                    return numericUserId;
                })
                .orElseGet(() -> {
                    logger.warn("JWT令牌缺少用户ID，且无法通过用户名解析数字用户ID: {}", username);
                    return username;
                });
        return new UserIdentity(username, traceUserId);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
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
    
    /**
     * 获取内部指令令牌 - 供前端调用
     */
    public static String getInternalCmdToken() {
        return INTERNAL_CMD_TOKEN;
    }
} 
