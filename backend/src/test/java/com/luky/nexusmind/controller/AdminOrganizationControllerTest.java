package com.luky.nexusmind.controller;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.SmtpSettingsRepository;
import com.luky.nexusmind.repository.UserRepository;
import com.luky.nexusmind.service.*;
import com.luky.nexusmind.utils.JwtUtils;
import com.luky.nexusmind.utils.PasswordUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminOrganizationControllerTest {
    @Test
    void auditsOnlyWhenPasswordFailuresReachRateLimit() {
        AuditService audit = mock(AuditService.class);
        UserRepository users = mock(UserRepository.class);
        JwtUtils jwt = mock(JwtUtils.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        AtomicLong failures = new AtomicLong();
        User actor = new User();
        actor.setId(1L);
        actor.setUsername("root");
        actor.setRole(User.Role.SUPER_ADMIN);
        actor.setPassword(PasswordUtil.encode("correct-password"));
        when(jwt.extractUsernameFromToken("token")).thenReturn("root");
        when(users.findByUsername("root")).thenReturn(Optional.of(actor));
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(ignored -> failures.get() == 0 ? null : String.valueOf(failures.get()));
        when(values.increment(anyString())).thenAnswer(ignored -> failures.incrementAndGet());
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        AdminOrganizationController controller = new AdminOrganizationController(
                mock(OrganizationService.class), audit, mock(SmtpSettingsRepository.class),
                mock(SmtpCryptoService.class), mock(MailService.class), users, jwt,
                mock(AccountStatusService.class), redis);
        AccountStatusRequest body = new AccountStatusRequest(false, "账户风险", "wrong-password");

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertEquals(HttpStatus.FORBIDDEN,
                    assertThrows(CustomException.class,
                            () -> controller.accountEnabled("Bearer token", 2L, body, request)).getStatus());
        }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS,
                assertThrows(CustomException.class,
                        () -> controller.accountEnabled("Bearer token", 2L, body, request)).getStatus());
        assertThrows(CustomException.class, () -> controller.accountEnabled("Bearer token", 2L, body, request));

        verify(audit, times(1)).record(actor, "SENSITIVE_REAUTH_RATE_LIMITED", 2L, null,
                "当前密码连续验证失败", "127.0.0.1");
    }
}
