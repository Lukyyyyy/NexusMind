package com.luky.nexusmind.service;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** 登录/重置密码接口的 Redis 滑动窗口限流：防撞库与 BCrypt CPU 放大。 */
@Service
public class LoginRateLimitService {
    private final StringRedisTemplate redis;
    private final int maxAttempts;
    private final long windowSeconds;
    private final long lockSeconds;

    public LoginRateLimitService(StringRedisTemplate redis,
            @Value("${security.login.max-attempts:5}") int maxAttempts,
            @Value("${security.login.window-seconds:60}") long windowSeconds,
            @Value("${security.login.lock-seconds:300}") long lockSeconds) {
        this.redis = redis;
        this.maxAttempts = maxAttempts;
        this.windowSeconds = windowSeconds;
        this.lockSeconds = lockSeconds;
    }

    public boolean isLocked(String key) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(lockKey(key)));
        } catch (Exception e) {
            return false;
        }
    }

    public void recordFailure(String key) {
        try {
            String counter = counterKey(key);
            Long count = redis.opsForValue().increment(counter);
            if (count != null && count == 1) {
                redis.expire(counter, Duration.ofSeconds(windowSeconds));
            }
            if (count != null && count >= maxAttempts) {
                redis.opsForValue().set(lockKey(key), "1", Duration.ofSeconds(lockSeconds));
                redis.delete(counter);
            }
        } catch (Exception ignored) {
        }
    }

    public void recordSuccess(String key) {
        try {
            redis.delete(counterKey(key));
        } catch (Exception ignored) {
        }
    }

    public long lockTtlSeconds(String key) {
        try {
            Long ttl = redis.getExpire(lockKey(key));
            return ttl == null ? lockSeconds : Math.max(ttl, 0);
        } catch (Exception e) {
            return lockSeconds;
        }
    }

    private String counterKey(String key) {
        return "nexusmind:login:fail:" + key;
    }

    private String lockKey(String key) {
        return "nexusmind:login:lock:" + key;
    }
}
