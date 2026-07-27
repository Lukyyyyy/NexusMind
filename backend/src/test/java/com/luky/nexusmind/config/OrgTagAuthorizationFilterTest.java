package com.luky.nexusmind.config;

import com.luky.nexusmind.utils.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrgTagAuthorizationFilterTest {

    @Test
    void knowledgeGraphRequestsReceiveIdentityAttributes() throws Exception {
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

        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/knowledge-graph/documents/0123456789abcdef0123456789abcdef");
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilterInternal(request, response, (filteredRequest, filteredResponse) -> continued.set(true));

        assertEquals("42", request.getAttribute("userId"));
        assertEquals("ADMIN", request.getAttribute("role"));
        assertEquals("default", request.getAttribute("orgTags"));
        assertTrue(continued.get());
    }
}
