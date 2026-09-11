package com.luky.nexusmind.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/** 短期、文件级下载能力；不在 URL 中泄露登录令牌或 MinIO 地址。默认一次性、可重放需显式放开。 */
@Service
public class DocumentDownloadTicketService {
    private static final String PREFIX = "nexusmind:download:";
    private final Duration ticketTtl;
    private final boolean singleUse;
    private final SecureRandom random = new SecureRandom();
    private final StringRedisTemplate redis;
    private final String publicUrl;

    public DocumentDownloadTicketService(StringRedisTemplate redis,
            @Value("${file.download.public-url:http://localhost:${server.port:18081}}") String publicUrl,
            @Value("${file.download.ticket-ttl:5m}") String ticketTtl,
            @Value("${file.download.ticket-single-use:true}") boolean singleUse) {
        this.ticketTtl = DurationStyle.detectAndParse(ticketTtl);
        if (this.ticketTtl.isZero() || this.ticketTtl.isNegative()) {
            throw new IllegalArgumentException("file.download.ticket-ttl 必须大于 0");
        }
        this.redis = redis;
        this.singleUse = singleUse;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    public String createUrl(String fileMd5) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(PREFIX + ticket, fileMd5, ticketTtl);
        return publicUrl + "/api/v1/documents/download/content?ticket=" + ticket;
    }

    public Optional<String> resolve(String ticket) {
        if (ticket == null || !ticket.matches("[A-Za-z0-9_-]{43}")) {
            return Optional.empty();
        }
        String key = PREFIX + ticket;
        if (singleUse) {
            return Optional.ofNullable(redis.opsForValue().getAndDelete(key));
        }
        return Optional.ofNullable(redis.opsForValue().get(key));
    }
}
