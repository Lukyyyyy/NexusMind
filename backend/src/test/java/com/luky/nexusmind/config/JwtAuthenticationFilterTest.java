package com.luky.nexusmind.config;

import com.luky.nexusmind.utils.JwtUtils;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {
    @Test
    void expiredAccessTokenCannotMintAnotherAccessToken() throws Exception {
        var jwt = mock(JwtUtils.class);
        var filter = new JwtAuthenticationFilter();
        ReflectionTestUtils.setField(filter, "jwtUtils", jwt);
        var request = new MockHttpServletRequest("GET", "/api/v1/documents");
        request.addHeader("Authorization", "Bearer expired");
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();

        filter.doFilter(request, response, chain);

        verify(jwt, never()).refreshToken(anyString());
        verify(jwt, never()).canRefreshExpiredToken(anyString());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(request, response);
    }
}
