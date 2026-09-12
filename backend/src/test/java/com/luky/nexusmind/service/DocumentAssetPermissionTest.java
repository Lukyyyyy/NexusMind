package com.luky.nexusmind.service;

import com.luky.nexusmind.model.FileUpload;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentAssetPermissionTest {
    @Test
    void failedImageCleanupKeepsDocumentRecordForRetry() throws Exception {
        DocumentService documents = new DocumentService();
        ParsedAssetService assets = mock(ParsedAssetService.class);
        var files = mock(com.luky.nexusmind.repository.FileUploadRepository.class);
        FileUpload file = new FileUpload();
        file.setFileMd5("file");
        file.setUserId("owner");
        when(files.lockAllByMd5("file")).thenReturn(List.of(file));
        ReflectionTestUtils.setField(documents, "fileUploadRepository", files);
        ReflectionTestUtils.setField(documents, "parsedAssetService", assets);
        ReflectionTestUtils.setField(documents, "knowledgeGraphService", mock(KnowledgeGraphService.class));
        doThrow(new java.io.IOException("storage unavailable")).when(assets).delete("file");
        assertThrows(RuntimeException.class, () -> documents.deleteDocument("file", "owner"));
        verify(files, never()).delete(any(FileUpload.class));
    }

    @Test
    void checksFileAccessBeforeReadingStorage() throws Exception {
        DocumentService documents = spy(new DocumentService());
        ParsedAssetService assets = mock(ParsedAssetService.class);
        ReflectionTestUtils.setField(documents, "parsedAssetService", assets);
        doReturn(List.of()).when(documents).getAccessibleFiles("outsider", "");
        assertThrows(RuntimeException.class, () -> documents.openParsedAsset("file", "image.jpg", "outsider", ""));
        verifyNoInteractions(assets);
        FileUpload file = new FileUpload();
        file.setFileMd5("file");
        doReturn(List.of(file)).when(documents).getAccessibleFiles("owner", "");
        documents.openParsedAsset("file", "image.jpg", "owner", "");
        verify(assets).open("file", "image.jpg");
    }
}
