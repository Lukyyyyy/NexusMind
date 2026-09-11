package com.luky.nexusmind.controller;

import com.luky.nexusmind.service.ChatHandler;
import com.luky.nexusmind.service.ChatWebSocketTicketService;
import com.luky.nexusmind.utils.LogUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;

@Component
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController extends TextWebSocketHandler {

    private final ChatHandler chatHandler;
    private final ChatWebSocketTicketService chatTickets;

    public ChatController(ChatHandler chatHandler, ChatWebSocketTicketService chatTickets) {
        this.chatHandler = chatHandler;
        this.chatTickets = chatTickets;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String userMessage = message.getPayload();
        String userId = session.getId(); // Use session ID as userId for simplicity

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("WEBSOCKET_CHAT");
        try {
            LogUtils.logChat(userId, session.getId(), "USER_MESSAGE", userMessage.length());
            LogUtils.logBusiness("WEBSOCKET_CHAT", userId, "处理WebSocket聊天消息: messageLength=%d", userMessage.length());

        chatHandler.processMessage(userId, userMessage, session);

            LogUtils.logUserOperation(userId, "WEBSOCKET_CHAT", "message_processing", "SUCCESS");
            monitor.end("WebSocket消息处理成功");
        } catch (Exception e) {
            LogUtils.logBusinessError("WEBSOCKET_CHAT", userId, "WebSocket消息处理失败", e);
            monitor.end("WebSocket消息处理失败: " + e.getMessage());
            throw e;
        }
    }

    /**
     * 签发聊天 WebSocket 一次性票据（需登录）。前端用票据建连，不再把登录 JWT 放入 URL。
     */
    @GetMapping("/ws-ticket")
    public ResponseEntity<?> getWebSocketTicket() {
        try {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()
                    || !(authentication.getPrincipal() instanceof UserDetails userDetails)) {
                return ResponseEntity.status(401).body(Map.of("code", 401, "message", "未登录"));
            }
            String ticket = chatTickets.issue(userDetails.getUsername());
            return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "获取聊天连接票据成功",
                "data", Map.of("ticket", ticket)
            ));
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_WEBSOCKET_TICKET", "system", "获取聊天连接票据失败", e);
            return ResponseEntity.status(500).body(Map.of(
                "code", 500,
                "message", "服务器内部错误"
            ));
        }
    }
}
