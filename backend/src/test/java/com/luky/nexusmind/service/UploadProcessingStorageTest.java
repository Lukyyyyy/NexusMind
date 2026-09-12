package com.luky.nexusmind.service;

import com.luky.nexusmind.model.FileUpload;
import com.luky.nexusmind.repository.FileUploadRepository;
import io.minio.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UploadProcessingStorageTest {
    @Test void readsStableObjectWithoutPresignedUrlAndPreservesStorageFailure() throws Exception {
        MinioClient minio = mock(MinioClient.class);
        UploadService service = new UploadService();
        String key = "d41d8cd98f00b204e9800998ecf8427e";
        FileUpload upload = new FileUpload();
        upload.setFileMd5(key);
        upload.setContentMd5(key);
        FileUploadRepository files = mock(FileUploadRepository.class);
        when(files.findByFileMd5(key)).thenReturn(java.util.Optional.of(upload));
        ReflectionTestUtils.setField(service, "fileUploadRepository", files);
        ReflectionTestUtils.setField(service, "minioClient", minio);
        ReflectionTestUtils.setField(service, "minioBucketName", "uploads");
        var stream = mock(GetObjectResponse.class);
        when(minio.getObject(any(GetObjectArgs.class))).thenReturn(stream);
        assertSame(stream, service.openMergedFile(key, "论文 + test.pdf"));
        verify(minio).getObject(argThat((GetObjectArgs args) ->
                "uploads".equals(args.bucket()) && "merged/d41d8cd98f00b204e9800998ecf8427e/论文 + test.pdf".equals(args.object())));
        verify(minio, never()).getPresignedObjectUrl(any());
        var failure = new java.io.IOException("connection refused");
        when(minio.getObject(any(GetObjectArgs.class))).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.openMergedFile(key, "论文 + test.pdf")).getCause());
    }

    @Test void newDocumentNeverFallsBackToGlobalFilenameObject() throws Exception {
        String key = "0123456789abcdef0123456789abcdef";
        FileUpload upload = new FileUpload();
        upload.setFileMd5(key);
        upload.setContentMd5("d41d8cd98f00b204e9800998ecf8427e");
        FileUploadRepository files = mock(FileUploadRepository.class);
        when(files.findByFileMd5(key)).thenReturn(java.util.Optional.of(upload));
        MinioClient minio = mock(MinioClient.class);
        var service = new UploadService();
        ReflectionTestUtils.setField(service, "fileUploadRepository", files);
        ReflectionTestUtils.setField(service, "minioClient", minio);
        ReflectionTestUtils.setField(service, "minioBucketName", "uploads");
        when(minio.getObject(any(GetObjectArgs.class))).thenThrow(new java.io.IOException("missing"));

        assertThrows(IllegalStateException.class, () -> service.openMergedFile(key, "同名.pdf"));
        verify(minio, times(1)).getObject(any(GetObjectArgs.class));
    }
}
