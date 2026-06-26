package com.luky.nexusmind.controller;

import com.luky.nexusmind.model.FileProcessingStatus;
import com.luky.nexusmind.model.FileUpload;
import com.luky.nexusmind.model.ParseEngine;
import com.luky.nexusmind.model.ProcessingStage;
import com.luky.nexusmind.model.ProcessingState;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.DocumentVectorRepository;
import com.luky.nexusmind.repository.OrganizationTagRepository;
import com.luky.nexusmind.repository.UserRepository;
import com.luky.nexusmind.service.DocumentService;
import com.luky.nexusmind.service.ElasticsearchService;
import com.luky.nexusmind.service.FileProcessingStatusService;
import com.luky.nexusmind.service.ProcessingStatusEventService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DocumentControllerTest {

    @Test
    void accessibleFilesUseFileOwnerProcessingStatusWhenViewerIsDifferentUser() {
        FileUpload adminFile = new FileUpload();
        adminFile.setFileMd5("0123456789abcdef0123456789abcdef");
        adminFile.setFileName("admin-file.pdf");
        adminFile.setTotalSize(1024L);
        adminFile.setStatus(1);
        adminFile.setUserId("1");
        adminFile.setPublic(true);
        adminFile.setOrgTag("default");
        adminFile.setCreatedAt(LocalDateTime.now());

        FileProcessingStatus adminStatus = new FileProcessingStatus();
        adminStatus.setFileMd5(adminFile.getFileMd5());
        adminStatus.setFileName(adminFile.getFileName());
        adminStatus.setUserId("1");
        adminStatus.setParseEngine(ParseEngine.AUTO);
        adminStatus.setActualParseEngine(ParseEngine.MINERU);
        adminStatus.setCurrentStage(ProcessingStage.COMPLETED);
        adminStatus.setState(ProcessingState.SUCCEEDED);
        adminStatus.setParsedChunkCount(40);
        adminStatus.setVectorizedCount(40);
        adminStatus.setEsDocumentCount(40L);
        adminStatus.setCreatedAt(LocalDateTime.now().minusSeconds(53));
        adminStatus.setCompletedAt(LocalDateTime.now());

        DocumentController controller = new DocumentController();
        ReflectionTestUtils.setField(controller, "documentService", new FixedDocumentService(List.of(adminFile)));
        ReflectionTestUtils.setField(controller, "processingStatusService", new OwnerScopedProcessingStatusService(adminStatus));
        ReflectionTestUtils.setField(controller, "processingStatusEventService", new ProcessingStatusEventService());
        ReflectionTestUtils.setField(controller, "documentVectorRepository", proxy(DocumentVectorRepository.class, (proxy, method, args) -> 0L));
        ReflectionTestUtils.setField(controller, "elasticsearchService", new ElasticsearchService());
        ReflectionTestUtils.setField(controller, "organizationTagRepository", proxy(OrganizationTagRepository.class, (proxy, method, args) -> Optional.empty()));
        ReflectionTestUtils.setField(controller, "userRepository", fixedUsers());

        ResponseEntity<?> response = controller.getAccessibleFiles("2", "default,PRIVATE_Jack", null, null, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("data");
        Map<String, Object> row = rows.get(0);
        assertEquals(ParseEngine.MINERU, row.get("actualParseEngine"));
        assertNotNull(row.get("processingDurationMillis"));
    }

    @Test
    void accessibleFilesCanBeFilteredByOrgTagAndPublicState() {
        FileUpload defaultPublicFile = file("default-file.pdf", "default", true);
        FileUpload audioPublicFile = file("audio-public.pdf", "audio", true);
        FileUpload audioPrivateFile = file("audio-private.pdf", "audio", false);

        DocumentController controller = new DocumentController();
        ReflectionTestUtils.setField(
                controller,
                "documentService",
                new FixedDocumentService(List.of(defaultPublicFile, audioPublicFile, audioPrivateFile))
        );
        ReflectionTestUtils.setField(controller, "processingStatusService", new OwnerScopedProcessingStatusService(null));
        ReflectionTestUtils.setField(controller, "processingStatusEventService", new ProcessingStatusEventService());
        ReflectionTestUtils.setField(controller, "documentVectorRepository", proxy(DocumentVectorRepository.class, (proxy, method, args) -> 0L));
        ReflectionTestUtils.setField(controller, "elasticsearchService", new ElasticsearchService());
        ReflectionTestUtils.setField(controller, "organizationTagRepository", proxy(OrganizationTagRepository.class, (proxy, method, args) -> Optional.empty()));
        ReflectionTestUtils.setField(controller, "userRepository", fixedUsers());

        ResponseEntity<?> response = controller.getAccessibleFiles("2", "default,audio", "audio", null, false);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("data");
        assertEquals(1, rows.size());
        assertEquals("audio-private.pdf", rows.get(0).get("fileName"));
        assertEquals("audio", rows.get(0).get("orgTag"));
        assertEquals(false, rows.get(0).get("public"));
    }

    @Test
    void accessibleFilesCanBeFilteredByMultipleOrgTags() {
        FileUpload defaultFile = file("default-file.pdf", "default", true);
        FileUpload audioFile = file("audio-file.pdf", "audio", true);
        FileUpload visionFile = file("vision-file.pdf", "vision", true);

        DocumentController controller = new DocumentController();
        ReflectionTestUtils.setField(
                controller,
                "documentService",
                new FixedDocumentService(List.of(defaultFile, audioFile, visionFile))
        );
        ReflectionTestUtils.setField(controller, "processingStatusService", new OwnerScopedProcessingStatusService(null));
        ReflectionTestUtils.setField(controller, "processingStatusEventService", new ProcessingStatusEventService());
        ReflectionTestUtils.setField(controller, "documentVectorRepository", proxy(DocumentVectorRepository.class, (proxy, method, args) -> 0L));
        ReflectionTestUtils.setField(controller, "elasticsearchService", new ElasticsearchService());
        ReflectionTestUtils.setField(controller, "organizationTagRepository", proxy(OrganizationTagRepository.class, (proxy, method, args) -> Optional.empty()));
        ReflectionTestUtils.setField(controller, "userRepository", fixedUsers());

        ResponseEntity<?> response = controller.getAccessibleFiles("2", "default,audio,vision", null, List.of("default", "audio"), null);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("data");
        assertEquals(2, rows.size());
        assertEquals(List.of("default-file.pdf", "audio-file.pdf"), rows.stream().map(row -> row.get("fileName")).toList());
    }

    private static FileUpload file(String fileName, String orgTag, boolean isPublic) {
        FileUpload file = new FileUpload();
        file.setFileMd5(String.format("%032x", fileName.hashCode()));
        file.setFileName(fileName);
        file.setTotalSize(1024L);
        file.setStatus(1);
        file.setUserId("1");
        file.setPublic(isPublic);
        file.setOrgTag(orgTag);
        file.setCreatedAt(LocalDateTime.now());
        return file;
    }

    private static UserRepository fixedUsers() {
        Map<Long, User> users = new HashMap<>();
        User admin = new User();
        admin.setId(1L);
        admin.setUsername("admin");
        users.put(1L, admin);
        User jack = new User();
        jack.setId(2L);
        jack.setUsername("Jack");
        users.put(2L, jack);

        return proxy(UserRepository.class, (proxy, method, args) -> switch (method.getName()) {
            case "findById" -> Optional.ofNullable(users.get((Long) args[0]));
            case "findByUsername" -> users.values().stream()
                    .filter(user -> user.getUsername().equals(args[0]))
                    .findFirst();
            default -> defaultValue(method.getReturnType());
        });
    }

    private static class FixedDocumentService extends DocumentService {
        private final List<FileUpload> files;

        private FixedDocumentService(List<FileUpload> files) {
            this.files = files;
        }

        @Override
        public List<FileUpload> getAccessibleFiles(String userId, String orgTags) {
            return files;
        }
    }

    private static class OwnerScopedProcessingStatusService extends FileProcessingStatusService {
        private final FileProcessingStatus status;

        private OwnerScopedProcessingStatusService(FileProcessingStatus status) {
            super(null, null);
            this.status = status;
        }

        @Override
        public Map<String, FileProcessingStatus> findLatestByFileMd5(Collection<String> fileMd5List, String userId) {
            if (status == null) {
                return Map.of();
            }
            if (!status.getUserId().equals(userId) || !fileMd5List.contains(status.getFileMd5())) {
                return Map.of();
            }
            return Map.of(status.getFileMd5(), status);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType.equals(boolean.class)) {
            return false;
        }
        if (returnType.equals(long.class) || returnType.equals(int.class)) {
            return 0;
        }
        if (returnType.equals(void.class)) {
            return null;
        }
        return null;
    }
}
