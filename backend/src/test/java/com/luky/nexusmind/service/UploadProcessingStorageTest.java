package com.luky.nexusmind.service;

import io.minio.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UploadProcessingStorageTest {
    @Test void readsStableObjectWithoutPresignedUrlAndPreservesStorageFailure() throws Exception {
        MinioClient minio = mock(MinioClient.class);
        UploadService service = new UploadService();
        ReflectionTestUtils.setField(service, "minioClient", minio);
        ReflectionTestUtils.setField(service, "minioBucketName", "uploads");
        var stream = mock(GetObjectResponse.class);
        when(minio.getObject(any(GetObjectArgs.class))).thenReturn(stream);
        assertSame(stream, service.openMergedFile("d41d8cd98f00b204e9800998ecf8427e", "论文 + test.pdf"));
        verify(minio).getObject(argThat((GetObjectArgs args) ->
                "uploads".equals(args.bucket()) && "merged/d41d8cd98f00b204e9800998ecf8427e/论文 + test.pdf".equals(args.object())));
        verify(minio, never()).getPresignedObjectUrl(any());
        var failure = new java.io.IOException("connection refused");
        when(minio.getObject(any(GetObjectArgs.class))).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.openMergedFile("d41d8cd98f00b204e9800998ecf8427e", "论文 + test.pdf")).getCause());
    }
}
