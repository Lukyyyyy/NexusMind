package com.luky.nexusmind.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.time.Duration;
import java.util.Map;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentDownloadTicketServiceTest {
    @Test
    void defaultsToFiveMinutesWithoutConfiguration() {
        verifyConfiguredTtl(null, Duration.ofMinutes(5));
    }

    @Test
    void usesConfiguredLifetime() {
        verifyConfiguredTtl("30m", Duration.ofMinutes(30));
    }

    @Test
    void rejectsNonPositiveOrInvalidLifetime() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        for (String value : new String[] {"0s", "-1m", "invalid"}) {
            assertThrows(IllegalArgumentException.class, () ->
                    new DocumentDownloadTicketService(redis, "http://localhost:18081", value, true));
        }
        verifyNoInteractions(redis);
    }

    @SuppressWarnings("unchecked")
    private void verifyConfiguredTtl(String value, Duration expected) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            if (value != null) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                        "test", Map.of("file.download.ticket-ttl", value)));
            }
            context.registerBean(StringRedisTemplate.class, () -> redis);
            context.register(DocumentDownloadTicketService.class);
            context.refresh();
            context.getBean(DocumentDownloadTicketService.class).createUrl("file-md5");
            verify(values).set(anyString(), eq("file-md5"), eq(expected));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void createsFileScopedExpiringTicketWithoutStorageOrLoginCredentials() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        DocumentDownloadTicketService service = new DocumentDownloadTicketService(redis,
                "https://nexusmind.lukybetter.com/", "5m", true);
        String url = service.createUrl("file-md5");
        assertTrue(url.startsWith("https://nexusmind.lukybetter.com/api/v1/documents/download/content?ticket="));
        String ticket = url.substring(url.indexOf("?ticket=") + 8);
        assertTrue(ticket.matches("[A-Za-z0-9_-]{43}"));
        verify(values).set("nexusmind:download:" + ticket, "file-md5", Duration.ofMinutes(5));
        when(values.getAndDelete("nexusmind:download:" + ticket)).thenReturn("file-md5");
        assertEquals("file-md5", service.resolve(ticket).orElseThrow());
        when(values.getAndDelete("nexusmind:download:" + ticket)).thenReturn(null);
        assertTrue(service.resolve(ticket).isEmpty());
    }

    @Test
    void rejectsMalformedTicketWithoutReadingRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        DocumentDownloadTicketService service = new DocumentDownloadTicketService(redis, "http://localhost:18081", "5m", true);
        assertTrue(service.resolve(null).isEmpty());
        assertTrue(service.resolve("../secret").isEmpty());
        assertTrue(service.resolve("").isEmpty());
        verifyNoInteractions(redis);
    }
}
