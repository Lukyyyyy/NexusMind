package com.luky.nexusmind.service;

import com.luky.nexusmind.model.FileUpload;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.FileUploadRepository;
import com.luky.nexusmind.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentServiceTest {
    private DocumentService documentService;
    private InMemoryFileUploadRepository fileUploads;
    private InMemoryUserRepository users;
    private FixedOrgTagCacheService cache;

    @BeforeEach
    void setUp() {
        fileUploads = new InMemoryFileUploadRepository();
        users = new InMemoryUserRepository();
        cache = new FixedOrgTagCacheService();

        documentService = new DocumentService();
        ReflectionTestUtils.setField(documentService, "fileUploadRepository", fileUploads.proxy());
        ReflectionTestUtils.setField(documentService, "userRepository", users.proxy());
        ReflectionTestUtils.setField(documentService, "orgTagCacheService", cache);
    }

    @Test
    void accessibleFilesQueryIncludesDefaultOrgAliases() {
        User user = new User();
        user.setId(1L);
        user.setUsername("jack");
        users.save(user);
        cache.effectiveTagsByUsername.put("jack", List.of("default"));

        documentService.getAccessibleFiles("jack", "default");

        assertTrue(fileUploads.lastOrgTags.contains("default"));
        assertTrue(fileUploads.lastOrgTags.contains("DEFAULT"));
        assertTrue(fileUploads.lastOrgTags.contains("默认组织"));
    }

    @Test
    void ordinaryUserCannotSeeAnotherUsersPrivateSpaceDocuments() {
        User user = user(1L, "jack", User.Role.USER);
        users.save(user);
        cache.effectiveTagsByUsername.put("jack", List.of("PRIVATE_jack", "PRIVATE_jill"));
        fileUploads.files = List.of(
                file("own-private.pdf", "jack", "PRIVATE_jack", false),
                file("other-private.pdf", "jill", "PRIVATE_jill", true),
                file("shared.pdf", "jill", "engineering", true)
        );

        List<FileUpload> result = documentService.getAccessibleFiles("jack", "", "USER");

        assertEquals(List.of("own-private.pdf", "shared.pdf"),
                result.stream().map(FileUpload::getFileName).toList());
    }

    @Test
    void administratorCanSeeAllPrivateSpaceDocuments() {
        User admin = user(9L, "root", User.Role.ADMIN);
        users.save(admin);
        fileUploads.files = List.of(
                file("alice-private.pdf", "alice", "PRIVATE_alice", false),
                file("bob-private.pdf", "bob", "PRIVATE_bob", false)
        );

        List<FileUpload> result = documentService.getAccessibleFiles("root", "", "ADMIN");

        assertEquals(List.of("alice-private.pdf", "bob-private.pdf"),
                result.stream().map(FileUpload::getFileName).toList());
    }

    @Test
    void administratorRoleIsResolvedForInternalDocumentAccessCalls() {
        User admin = user(9L, "root", User.Role.ADMIN);
        users.save(admin);
        fileUploads.files = List.of(file("alice-private.pdf", "alice", "PRIVATE_alice", false));

        List<FileUpload> result = documentService.getAccessibleFiles("root", "");

        assertEquals(List.of("alice-private.pdf"), result.stream().map(FileUpload::getFileName).toList());
    }

    private static User user(Long id, String username, User.Role role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        return user;
    }

    private static FileUpload file(String name, String owner, String orgTag, boolean isPublic) {
        FileUpload file = new FileUpload();
        file.setFileName(name);
        file.setUserId(owner);
        file.setOrgTag(orgTag);
        file.setPublic(isPublic);
        return file;
    }

    private static class FixedOrgTagCacheService extends OrgTagCacheService {
        private final Map<String, List<String>> effectiveTagsByUsername = new java.util.HashMap<>();

        @Override
        public List<String> getUserEffectiveOrgTags(String username) {
            return effectiveTagsByUsername.getOrDefault(username, List.of());
        }
    }

    private static class InMemoryFileUploadRepository {
        private List<String> lastOrgTags = List.of();
        private List<FileUpload> files = List.of();

        FileUploadRepository proxy() {
            return DocumentServiceTest.proxy(FileUploadRepository.class, (proxy, method, args) -> switch (method.getName()) {
                case "findAccessibleFilesWithOwnersAndTags" -> {
                    lastOrgTags = new ArrayList<>((List<String>) args[1]);
                    yield files;
                }
                case "findByUserIdsOrIsPublicTrue", "findAll" -> files;
                default -> defaultValue(method.getReturnType());
            });
        }
    }

    private static class InMemoryUserRepository {
        private final Map<String, User> byUsername = new java.util.HashMap<>();
        private final Map<Long, User> byId = new java.util.HashMap<>();

        UserRepository proxy() {
            return DocumentServiceTest.proxy(UserRepository.class, (proxy, method, args) -> switch (method.getName()) {
                case "findByUsername" -> Optional.ofNullable(byUsername.get((String) args[0]));
                case "findById" -> Optional.ofNullable(byId.get((Long) args[0]));
                default -> defaultValue(method.getReturnType());
            });
        }

        void save(User user) {
            byUsername.put(user.getUsername(), user);
            byId.put(user.getId(), user);
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
