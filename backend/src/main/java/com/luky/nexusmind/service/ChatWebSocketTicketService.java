package com.luky.nexusmind.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** 聊天 WebSocket 一次性短期票据：登录 JWT 不再出现在 URL 中。 */
@Service
public class ChatWebSocketTicketService {
    private static final String PREFIX = "nexusmind:chat-ws:";
    private static final Duration TTL = Duration.ofSeconds(60);
    private final SecureRandom random = new SecureRandom();
    private final StringRedisTemplate redis;

    public ChatWebSocketTicketService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String issue(String username) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(PREFIX + ticket, username, TTL);
        return ticket;
    }

    public String consume(String ticket) {
        if (ticket == null || !ticket.matches("[A-Za-z0-9_-]{43}")) {
            return null;
        }
        return redis.opsForValue().getAndDelete(PREFIX + ticket);
    }
}
