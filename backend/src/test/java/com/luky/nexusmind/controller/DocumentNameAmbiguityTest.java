package com.luky.nexusmind.controller;

import com.luky.nexusmind.model.FileUpload;
import com.luky.nexusmind.repository.FileUploadRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class DocumentNameAmbiguityTest {
    @Test
    void anonymousDownloadRequiresDocumentKeyWhenNamesCollide() {
        SecurityContextHolder.clearContext();
        var first = new FileUpload();
        first.setFileName("paper.pdf");
        first.setFileMd5("11111111111111111111111111111111");
        var second = new FileUpload();
        second.setFileName("paper.pdf");
        second.setFileMd5("22222222222222222222222222222222");
        var files = mock(FileUploadRepository.class);
        when(files.findAllByFileNameAndIsPublicTrue("paper.pdf")).thenReturn(List.of(first, second));
        var controller = new DocumentController();
        ReflectionTestUtils.setField(controller, "fileUploadRepository", files);

        assertEquals(HttpStatus.CONFLICT, controller.downloadFileByName("paper.pdf", null).getStatusCode());
        verify(files).findAllByFileNameAndIsPublicTrue("paper.pdf");
    }
}
