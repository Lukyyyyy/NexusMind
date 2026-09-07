package com.luky.nexusmind.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.model.SystemNotification;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.SystemNotificationRepository;
import com.luky.nexusmind.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class NotificationService {
    public static final String CHANNEL = "nexusmind:notifications";
    private final SystemNotificationRepository repository;
    private final UserRepository userRepository;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public NotificationService(SystemNotificationRepository repository, UserRepository userRepository,
                               StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public SystemNotification notify(User recipient, String type, String title, String content, String link) {
        SystemNotification value = new SystemNotification();
        value.setRecipient(recipient);
        value.setType(type);
        value.setTitle(title);
        value.setContent(content);
        value.setLink(link);
        SystemNotification saved = repository.save(value);
        Runnable publish = () -> publish(recipient.getId(), "notification", view(saved));
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publish.run(); }
            });
        } else publish.run();
        return saved;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(String username, int page, int size) {
        User user = requireUser(username);
        Page<SystemNotification> values = repository.findByRecipientIdOrderByCreatedAtDesc(
                user.getId(), PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 100)));
        return Map.of("content", values.map(this::view).getContent(), "page", page, "size", size,
                "totalElements", values.getTotalElements(), "unread", repository.countByRecipientIdAndReadAtIsNull(user.getId()));
    }

    @Transactional
    public void markRead(String username, Long id) {
        User user = requireUser(username);
        SystemNotification value = repository.findById(id)
                .filter(item -> item.getRecipient().getId().equals(user.getId()))
                .orElseThrow(() -> new CustomException("通知不存在", HttpStatus.NOT_FOUND));
        if (value.getReadAt() == null) {
            value.setReadAt(LocalDateTime.now());
            repository.save(value);
            publish(user.getId(), "read", Map.of("id", id, "unread", repository.countByRecipientIdAndReadAtIsNull(user.getId())));
        }
    }

    @Transactional
    public void markAllRead(String username) {
        User user = requireUser(username);
        repository.findByRecipientIdOrderByCreatedAtDesc(user.getId(), PageRequest.of(0, 500)).forEach(value -> {
            if (value.getReadAt() == null) value.setReadAt(LocalDateTime.now());
        });
        publish(user.getId(), "read_all", Map.of("unread", 0));
    }

    @Scheduled(cron = "0 20 3 * * *")
    @Transactional
    public void cleanExpired() {
        repository.deleteByCreatedAtBefore(LocalDateTime.now().minusDays(180));
    }

    private User requireUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
    }

    private void publish(Long userId, String event, Object data) {
        try {
            redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(Map.of("userId", userId, "event", event, "data", data)));
        } catch (Exception ignored) {
            // ponytail: durable database notifications remain the fallback when Redis is unavailable.
        }
    }

    private Map<String, Object> view(SystemNotification value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", value.getId());
        result.put("type", value.getType());
        result.put("title", value.getTitle());
        result.put("content", value.getContent());
        result.put("link", value.getLink());
        result.put("read", value.getReadAt() != null);
        result.put("createdAt", value.getCreatedAt());
        return result;
    }
}
