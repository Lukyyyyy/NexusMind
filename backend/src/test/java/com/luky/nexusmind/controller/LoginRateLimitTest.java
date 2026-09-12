package com.luky.nexusmind.controller;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.service.LoginRateLimitService;
import com.luky.nexusmind.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class LoginRateLimitTest {
    @Test
    void invalidCredentialsCountAgainstAccountAndTrustedProxyIp() {
        var users = mock(UserService.class);
        var limiter = mock(LoginRateLimitService.class);
        var request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Real-IP")).thenReturn("203.0.113.7");
        when(request.getHeader("X-Forwarded-For")).thenReturn("8.8.8.8, 203.0.113.7");
        when(users.authenticateUser("User@Example.com", "bad"))
                .thenThrow(new CustomException("邮箱或密码错误", HttpStatus.UNAUTHORIZED));
        var controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", users);
        ReflectionTestUtils.setField(controller, "loginRateLimitService", limiter);

        var response = controller.login(new LoginRequest("User@Example.com", "bad"), request);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(limiter).recordFailure("ip:203.0.113.7");
        verify(limiter).recordFailure("email:" + DigestUtils.sha256Hex("user@example.com"));
        verify(limiter, never()).recordFailure("ip:8.8.8.8");
    }
}
