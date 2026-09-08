package com.luky.nexusmind.controller;

import com.luky.nexusmind.model.ChatMessage;
import com.luky.nexusmind.model.ChatSession;
import com.luky.nexusmind.model.ChatScopeType;
import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.service.ChatScopeService;
import com.luky.nexusmind.service.ChatSessionService;
import com.luky.nexusmind.utils.JwtUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/chat/sessions")
public class ChatSessionController {

    private final ChatSessionService chatSessionService;
    private final JwtUtils jwtUtils;
    private final ChatScopeService chatScopeService;

    public ChatSessionController(ChatSessionService chatSessionService, JwtUtils jwtUtils,
                                 ChatScopeService chatScopeService) {
        this.chatSessionService = chatSessionService;
        this.jwtUtils = jwtUtils;
        this.chatScopeService = chatScopeService;
    }

    @PostMapping
    public ResponseEntity<?> createSession(@RequestHeader("Authorization") String token,
                                           @RequestBody(required = false) ScopeRequest request) {
        String username = username(token);
        ChatScopeService.ScopeSelection scope = request == null
                ? chatScopeService.select(username, ChatScopeType.ALL, null, List.of())
                : chatScopeService.select(username, request.type(), request.orgTag(), request.documentIds());
        ChatSession session = chatSessionService.createSession(username, scope);
        return ok("创建会话成功", sessionData(session));
    }

    @GetMapping("/scope-options")
    public ResponseEntity<?> scopeOptions(@RequestHeader("Authorization") String token) {
        return ok("获取问答范围成功", chatScopeService.options(username(token)));
    }

    @GetMapping
    public ResponseEntity<?> listSessions(@RequestHeader("Authorization") String token) {
        String username = username(token);
        List<Map<String, Object>> data = chatSessionService.listSessions(username)
                .stream()
                .map(this::sessionData)
                .toList();
        return ok("获取会话列表成功", data);
    }

    @GetMapping("/{sessionId}/messages")
    public ResponseEntity<?> getMessages(@RequestHeader("Authorization") String token,
                                         @PathVariable Long sessionId) {
        String username = username(token);
        List<Map<String, Object>> data = chatSessionService.getMessages(username, sessionId)
                .stream()
                .map(this::messageData)
                .toList();
        return ok("获取会话消息成功", data);
    }

    @PatchMapping("/{sessionId}")
    public ResponseEntity<?> renameSession(@RequestHeader("Authorization") String token,
                                           @PathVariable Long sessionId,
                                           @RequestBody SessionUpdateRequest request) {
        String username = username(token);
        ChatSession session = chatSessionService.renameSession(username, sessionId, request.title());
        return ok("重命名会话成功", sessionData(session));
    }

    @PatchMapping("/{sessionId}/scope")
    public ResponseEntity<?> updateScope(@RequestHeader("Authorization") String token,
                                         @PathVariable Long sessionId,
                                         @RequestBody ScopeRequest request) {
        String username = username(token);
        ChatScopeService.ScopeSelection scope = chatScopeService.select(
                username, request.type(), request.orgTag(), request.documentIds());
        return ok("更新问答范围成功", sessionData(chatSessionService.updateScope(username, sessionId, scope)));
    }

    @PatchMapping("/{sessionId}/model")
    public ResponseEntity<?> updateModel(@RequestHeader("Authorization") String token,
                                         @PathVariable Long sessionId,
                                         @RequestBody ModelRequest request) {
        return ok("切换模型成功", sessionData(chatSessionService.changeModel(
                username(token), sessionId, request == null ? null : request.modelConfigId())));
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<?> deleteSession(@RequestHeader("Authorization") String token,
                                           @PathVariable Long sessionId) {
        String username = username(token);
        chatSessionService.deleteSession(username, sessionId);
        return ok("删除会话成功", null);
    }

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<?> handleCustomException(CustomException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of(
                "code", e.getStatus().value(),
                "message", e.getMessage(),
                "data", Map.of()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleException(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "code", 500,
                "message", "服务器内部错误: " + e.getMessage(),
                "data", Map.of()
        ));
    }

    private String username(String token) {
        return jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
    }

    private ResponseEntity<?> ok(String message, Object data) {
        return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", message,
                "data", data == null ? Map.of() : data
        ));
    }

    private Map<String, Object> sessionData(ChatSession session) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", session.getId());
        data.put("title", session.getTitle());
        data.put("titleGenerated", session.isTitleGenerated());
        data.put("createdAt", session.getCreatedAt());
        data.put("updatedAt", session.getUpdatedAt());
        data.put("scope", ChatScopeService.view(session));
        data.put("modelConfigId", session.getLlmConfigId());
        data.put("modelName", session.getLlmModelName());
        return data;
    }

    private Map<String, Object> messageData(ChatMessage message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", message.getId());
        data.put("role", message.getRole());
        data.put("content", message.getContent());
        data.put("status", message.getStatus());
        data.put("agentTrace", message.getAgentTrace());
        data.put("thinkingDurationMs", message.getThinkingDurationMs());
        data.put("timestamp", message.getCreatedAt());
        return data;
    }

    public record SessionUpdateRequest(String title) {
    }

    public record ScopeRequest(ChatScopeType type, String orgTag, List<Long> documentIds) {
    }

    public record ModelRequest(Long modelConfigId) {
    }
}
