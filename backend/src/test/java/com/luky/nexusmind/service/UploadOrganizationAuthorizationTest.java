package com.luky.nexusmind.service;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.model.OrganizationTag;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.OrganizationTagRepository;
import com.luky.nexusmind.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UploadOrganizationAuthorizationTest {
    @Test
    void requesterCannotAssignAnotherOrganizationsTag() {
        var users = mock(UserRepository.class);
        var organizations = mock(OrganizationTagRepository.class);
        var memberships = mock(OrganizationMembershipService.class);
        var user = new User();
        user.setId(7L);
        user.setRole(User.Role.USER);
        user.setUsername("alice");
        var tag = new OrganizationTag();
        tag.setTagId("finance");
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(organizations.findByTagId("finance")).thenReturn(Optional.of(tag));
        var service = new UserService();
        ReflectionTestUtils.setField(service, "userRepository", users);
        ReflectionTestUtils.setField(service, "organizationTagRepository", organizations);
        ReflectionTestUtils.setField(service, "organizationMembershipService", memberships);

        CustomException denied = assertThrows(CustomException.class,
                () -> service.validateUploadOrgTag("7", "finance"));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatus());
        when(memberships.directMember(user, "finance")).thenReturn(true);
        assertEquals("finance", service.validateUploadOrgTag("7", "finance"));
    }
}
