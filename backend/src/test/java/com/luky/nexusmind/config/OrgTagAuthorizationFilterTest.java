package com.luky.nexusmind.config;

import com.luky.nexusmind.utils.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrgTagAuthorizationFilterTest {

    @org.junit.jupiter.api.AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "/api/v1/upload/generation",
            "/api/v1/knowledge-graph/documents/0123456789abcdef0123456789abcdef",
            "/api/v1/documents/0123456789abcdef0123456789abcdef/assets/abcdef.jpg"
    })
    void documentRequestsReceiveIdentityAttributes(String path) throws Exception {
        JwtUtils jwtUtils = new JwtUtils() {
            @Override
            public String extractUserIdFromToken(String token) {
                return "42";
            }

            @Override
            public String extractRoleFromToken(String token) {
                return "ADMIN";
            }

            @Override
            public String extractOrgTagsFromToken(String token) {
                return "default";
            }
        };

        OrgTagAuthorizationFilter filter = new OrgTagAuthorizationFilter();
        ReflectionTestUtils.setField(filter, "jwtUtils", jwtUtils);
        authenticateAs("ROLE_ADMIN");

        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", path);
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilterInternal(request, response, (filteredRequest, filteredResponse) -> continued.set(true));

        assertEquals("42", request.getAttribute("userId"));
        assertEquals("ADMIN", request.getAttribute("role"));
        assertEquals("default", request.getAttribute("orgTags"));
        assertTrue(continued.get());
    }

    @Test
    void currentRoleOverridesStaleTokenRole() throws Exception {
        JwtUtils jwtUtils = new JwtUtils() {
            @Override public String extractUserIdFromToken(String token) { return "42"; }
            @Override public String extractRoleFromToken(String token) { return "SUPER_ADMIN"; }
            @Override public String extractOrgTagsFromToken(String token) { return "default"; }
        };
        OrgTagAuthorizationFilter filter = new OrgTagAuthorizationFilter();
        ReflectionTestUtils.setField(filter, "jwtUtils", jwtUtils);
        authenticateAs("ROLE_ADMIN");
        MockHttpServletRequest request = new MockHttpServletRequest(
                "DELETE", "/api/v1/documents/0123456789abcdef0123456789abcdef");
        request.addHeader("Authorization", "Bearer token");

        filter.doFilterInternal(request, new MockHttpServletResponse(), (filteredRequest, filteredResponse) -> {});

        assertEquals("ADMIN", request.getAttribute("role"));
    }

    @Test
    void organizationGraphRequestsReceiveIdentityAttributes() throws Exception {
        JwtUtils jwtUtils = new JwtUtils() {
            @Override
            public String extractUserIdFromToken(String token) {
                return "42";
            }

            @Override
            public String extractRoleFromToken(String token) {
                return "USER";
            }

            @Override
            public String extractOrgTagsFromToken(String token) {
                return "研发部";
            }
        };

        OrgTagAuthorizationFilter filter = new OrgTagAuthorizationFilter();
        ReflectionTestUtils.setField(filter, "jwtUtils", jwtUtils);
        authenticateAs("ROLE_USER");
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/knowledge-graph/organizations/%E7%A0%94%E5%8F%91%E9%83%A8");
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilterInternal(request, response, (filteredRequest, filteredResponse) -> continued.set(true));

        assertEquals("42", request.getAttribute("userId"));
        assertEquals("USER", request.getAttribute("role"));
        assertEquals("研发部", request.getAttribute("orgTags"));
        assertTrue(continued.get());
    }

    private static void authenticateAs(String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "user", null, List.of(new SimpleGrantedAuthority(authority))));
    }
}
