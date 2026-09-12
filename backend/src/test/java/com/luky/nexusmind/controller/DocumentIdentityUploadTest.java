package com.luky.nexusmind.controller;

import com.luky.nexusmind.model.FileUpload;
import com.luky.nexusmind.repository.FileUploadRepository;
import com.luky.nexusmind.service.DocumentIdentity;
import com.luky.nexusmind.service.FileTaskControl;
import com.luky.nexusmind.service.UploadService;
import com.luky.nexusmind.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentIdentityUploadTest {
    private static final String MD5 = "d41d8cd98f00b204e9800998ecf8427e";
    private static final String SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void sameContentHasSeparateDocumentKeysAcrossOwnersAndOrganizations() {
        assertEquals(DocumentIdentity.key("alice", "team-a", SHA), DocumentIdentity.key("alice", "team-a", SHA));
        assertNotEquals(DocumentIdentity.key("alice", "team-a", SHA), DocumentIdentity.key("bob", "team-a", SHA));
        assertNotEquals(DocumentIdentity.key("alice", "team-a", SHA), DocumentIdentity.key("alice", "team-b", SHA));
    }

    @Test
    void completedDuplicateIsRejectedBeforeAnyUploadGeneration() {
        var files = mock(FileUploadRepository.class);
        var existing = new FileUpload();
        existing.setStatus(1);
        when(files.findDuplicate("alice", "team-a", SHA, MD5)).thenReturn(Optional.of(existing));
        var control = mock(FileTaskControl.class);
        var users = mock(UserService.class);
        when(users.validateUploadOrgTag("alice", "team-a")).thenReturn("team-a");
        @SuppressWarnings("unchecked")
        var kafka = (KafkaTemplate<String, Object>) mock(KafkaTemplate.class);
        var controller = new UploadController(mock(UploadService.class), kafka);
        ReflectionTestUtils.setField(controller, "fileUploadRepository", files);
        ReflectionTestUtils.setField(controller, "taskControl", control);
        ReflectionTestUtils.setField(controller, "userService", users);

        var response = controller.uploadGeneration(MD5, SHA, "team-a", "alice");
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verifyNoInteractions(control);
    }
}
