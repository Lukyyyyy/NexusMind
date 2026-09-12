package com.luky.nexusmind.service;

import com.luky.nexusmind.model.FileUpload;
import com.luky.nexusmind.repository.DocumentVectorRepository;
import com.luky.nexusmind.repository.FileUploadRepository;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.*;

class DocumentSharedDeletionTest {
    @Test
    void deletingOneOwnerKeepsSharedDataAndOtherOwnersChunks() throws Exception {
        String md5 = "d41d8cd98f00b204e9800998ecf8427e";
        var mine = new FileUpload();
        mine.setFileMd5(md5);
        mine.setUserId("alice");
        var other = new FileUpload();
        other.setFileMd5(md5);
        other.setUserId("bob");
        var files = mock(FileUploadRepository.class);
        when(files.lockAllByMd5(md5)).thenReturn(List.of(mine, other));
        var upload = mock(UploadService.class);
        var graph = mock(KnowledgeGraphService.class);
        var search = mock(ElasticsearchService.class);
        var vectors = mock(DocumentVectorRepository.class);
        var assets = mock(ParsedAssetService.class);
        var statuses = mock(FileProcessingStatusService.class);
        var minio = mock(MinioClient.class);
        var service = new DocumentService();
        ReflectionTestUtils.setField(service, "fileUploadRepository", files);
        ReflectionTestUtils.setField(service, "uploadService", upload);
        ReflectionTestUtils.setField(service, "knowledgeGraphService", graph);
        ReflectionTestUtils.setField(service, "elasticsearchService", search);
        ReflectionTestUtils.setField(service, "documentVectorRepository", vectors);
        ReflectionTestUtils.setField(service, "parsedAssetService", assets);
        ReflectionTestUtils.setField(service, "processingStatusService", statuses);
        ReflectionTestUtils.setField(service, "minioClient", minio);

        service.deleteDocument(md5, "alice");

        verify(graph).removeDocument(mine);
        verify(upload).deleteUploadChunks(md5, "alice");
        verify(upload, never()).deleteUploadChunks(md5, "bob");
        verify(files).delete(mine);
        verifyNoInteractions(search, vectors, assets, minio);
        assertNotEquals(UploadService.chunkPrefix(md5, "alice"), UploadService.chunkPrefix(md5, "bob"));
    }
}
