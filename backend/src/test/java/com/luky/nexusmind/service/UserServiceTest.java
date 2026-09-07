package com.luky.nexusmind.service;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.model.OrganizationTag;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.OrganizationTagRepository;
import com.luky.nexusmind.repository.UserRepository;
import com.luky.nexusmind.utils.PasswordUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UserService 的测试类
 */
class UserServiceTest {
    private UserService userService;
    private InMemoryUserRepository users;
    private InMemoryOrganizationTagRepository organizationTags;
    private RecordingOrgTagCacheService cache;
    private RecordingEmailVerificationService emailVerificationService;

    /**
     * 在每个测试方法执行前初始化测试替身
     */
    @BeforeEach
    void setUp() {
        users = new InMemoryUserRepository();
        organizationTags = new InMemoryOrganizationTagRepository();
        cache = new RecordingOrgTagCacheService();
        emailVerificationService = new RecordingEmailVerificationService();

        userService = new UserService();
        ReflectionTestUtils.setField(userService, "userRepository", users.proxy());
        ReflectionTestUtils.setField(userService, "organizationTagRepository", organizationTags.proxy());
        ReflectionTestUtils.setField(userService, "orgTagCacheService", cache);
        ReflectionTestUtils.setField(userService, "emailVerificationService", emailVerificationService);
    }

    /**
     * 测试用户注册成功的情况
     */
    @Test
    void testRegisterUser_Success() {
        organizationTags.save(existingTag("default", "默认组织"));

        userService.registerUser("testuser", "张三", "password123");

        User savedUser = users.findByUsername("testuser").orElseThrow();
        assertNotNull(savedUser);
        assertEquals("testuser", savedUser.getUsername());
        assertEquals("张三", savedUser.getDisplayName());
        assertEquals("default,PRIVATE_testuser", savedUser.getOrgTags());
        assertEquals("PRIVATE_testuser", savedUser.getPrimaryOrg());

        OrganizationTag privateTag = organizationTags.findByTagId("PRIVATE_testuser").orElseThrow();
        assertEquals("张三的私人空间", privateTag.getName());
        assertSame(savedUser, privateTag.getCreatedBy());

        assertEquals(List.of("default", "PRIVATE_testuser"), cache.cachedOrgTags.get("testuser"));
        assertEquals("PRIVATE_testuser", cache.cachedPrimaryOrg.get("testuser"));
    }

    /**
     * 测试用户注册时用户名已存在的情况
     */
    @Test
    void testRegisterUser_UsernameExists() {
        User existingUser = new User();
        existingUser.setUsername("testuser");
        users.save(existingUser);

        CustomException exception = assertThrows(
                CustomException.class,
                () -> userService.registerUser("testuser", "password123")
        );
        assertEquals("用户名已存在", exception.getMessage());
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    @Test
    void registeredEmailGetsGeneratedIdAndNickname() {
        organizationTags.save(existingTag("default", "默认组织"));
        User user = userService.registerVerifiedUser("HELLO@example.com", "123456", "password.123");

        assertTrue(user.getUsername().matches("zs_[a-z0-9]{10}"));
        assertEquals("用户_" + user.getUsername().substring(7), user.getDisplayName());
        assertEquals("hello@example.com", user.getEmail());
        assertNotNull(user.getEmailVerifiedAt());
        assertEquals("hello@example.com", emailVerificationService.consumedEmail);
    }

    /**
     * 测试用户认证成功的情况
     */
    @Test
    void testAuthenticateUser_Success() {
        String rawPassword = "password123";
        String encodedPassword = PasswordUtil.encode(rawPassword);

        User user = new User();
        user.setUsername("testuser");
        user.setEmail("test@example.com");
        user.setEmailVerifiedAt(LocalDateTime.now());
        user.setPassword(encodedPassword);
        users.save(user);

        String username = userService.authenticateUser("TEST@example.com", rawPassword);

        assertEquals("testuser", username);
    }

    /**
     * 测试用户认证失败的情况
     */
    @Test
    void testAuthenticateUser_InvalidCredentials() {
        CustomException exception = assertThrows(
                CustomException.class,
                () -> userService.authenticateUser("testuser", "wrongpassword")
        );
        assertEquals("邮箱或密码错误", exception.getMessage());
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
    }

    @Test
    void disabledAccountIsRevealedOnlyAfterCorrectPassword() {
        User user = new User();
        user.setUsername("testuser");
        user.setEmail("test@example.com");
        user.setEmailVerifiedAt(LocalDateTime.now());
        user.setPassword(PasswordUtil.encode("correct-password"));
        user.setEnabled(false);
        users.save(user);

        assertEquals(HttpStatus.UNAUTHORIZED,
                assertThrows(CustomException.class,
                        () -> userService.authenticateUser("test@example.com", "wrong-password")).getStatus());
        CustomException disabled = assertThrows(CustomException.class,
                () -> userService.authenticateUser("test@example.com", "correct-password"));
        assertEquals("ACCOUNT_DISABLED", disabled.getCode());
    }

    @Test
    void resetPasswordConsumesCodeAndChangesPassword() {
        User user = new User();
        user.setUsername("testuser");
        user.setEmail("test@example.com");
        user.setPassword(PasswordUtil.encode("oldpassword"));
        users.save(user);

        Long userId = userService.resetPassword("TEST@example.com", "123456", "newpassword");

        assertEquals(user.getId(), userId);
        assertTrue(PasswordUtil.matches("newpassword", user.getPassword()));
        assertEquals("test@example.com", emailVerificationService.resetEmail);
    }

    @Test
    void testRegisterUser_PasswordTooShort() {
        CustomException exception = assertThrows(
                CustomException.class,
                () -> userService.registerUser("testuser", "abc1234")
        );
        assertEquals("密码需为8-72个字符，不能包含控制字符", exception.getMessage());
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    @Test
    void testRegisterUser_RejectsInvalidDisplayName() {
        CustomException exception = assertThrows(
                CustomException.class,
                () -> userService.registerUser("testuser", "\n", "password123")
        );
        assertEquals("昵称需为1-32个字符，不能包含控制字符", exception.getMessage());
    }

    @Test
    void getUserOrgTagsDeduplicatesCachedTags() {
        organizationTags.save(existingTag("default", "默认组织"));
        organizationTags.save(existingTag("PRIVATE_jack", "Jack的私人空间"));
        User user = new User();
        user.setUsername("jack");
        user.setPassword(PasswordUtil.encode("password123"));
        user.setOrgTags("default,PRIVATE_jack");
        user.setPrimaryOrg("PRIVATE_jack");
        users.save(user);
        cache.cachedOrgTags.put("jack", List.of("PRIVATE_jack", "default", "PRIVATE_jack"));
        cache.cachedPrimaryOrg.put("jack", "PRIVATE_jack");

        Map<String, Object> orgTags = userService.getUserOrgTags("jack");

        assertEquals(List.of("PRIVATE_jack", "default"), orgTags.get("orgTags"));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> details = (List<Map<String, String>>) orgTags.get("orgTagDetails");
        assertEquals(List.of("PRIVATE_jack", "default"), details.stream().map(tag -> tag.get("tagId")).toList());
    }

    @Test
    void assigningOrganizationInvalidatesEffectiveMembershipImmediately() {
        OrganizationTag engineering = existingTag("engineering", "研发部");
        organizationTags.save(engineering);
        User admin = new User();
        admin.setUsername("admin");
        admin.setRole(User.Role.ADMIN);
        users.save(admin);
        User jack = new User();
        jack.setUsername("jack");
        jack.setRole(User.Role.USER);
        jack.setOrgTags("PRIVATE_jack");
        users.save(jack);

        userService.assignOrgTagsToUser(jack.getId(), List.of("engineering"), "admin");

        assertTrue(List.of(jack.getOrgTags().split(",")).contains("engineering"));
        assertTrue(cache.invalidatedEffectiveTags.contains("jack"));
    }

    private static OrganizationTag existingTag(String tagId, String name) {
        OrganizationTag tag = new OrganizationTag();
        tag.setTagId(tagId);
        tag.setName(name);
        tag.setDescription(name);
        return tag;
    }

    private static class RecordingOrgTagCacheService extends OrgTagCacheService {
        private final Map<String, List<String>> cachedOrgTags = new HashMap<>();
        private final Map<String, String> cachedPrimaryOrg = new HashMap<>();
        private final List<String> invalidatedEffectiveTags = new ArrayList<>();

        @Override
        public void cacheUserOrgTags(String username, List<String> orgTags) {
            cachedOrgTags.put(username, new ArrayList<>(orgTags));
        }

        @Override
        public void cacheUserPrimaryOrg(String username, String primaryOrg) {
            cachedPrimaryOrg.put(username, primaryOrg);
        }

        @Override
        public List<String> getUserOrgTags(String username) {
            return cachedOrgTags.get(username);
        }

        @Override
        public void deleteUserOrgTagsCache(String username) {
            cachedOrgTags.remove(username);
        }

        @Override
        public String getUserPrimaryOrg(String username) {
            return cachedPrimaryOrg.get(username);
        }

        @Override
        public void deleteUserEffectiveTagsCache(String username) {
            invalidatedEffectiveTags.add(username);
        }
    }

    private static class RecordingEmailVerificationService extends EmailVerificationService {
        private String consumedEmail;
        private String resetEmail;

        RecordingEmailVerificationService() {
            super(null, null, null);
        }

        @Override
        public String normalize(String email) {
            return email.trim().toLowerCase();
        }

        @Override
        public Long verifyRegistrationCode(String email, String code) {
            return 7L;
        }

        @Override
        public void consumeRegistrationCode(Long tokenId, String email) {
            consumedEmail = email;
        }

        @Override
        public Long verifyPasswordResetCode(User user, String email, String code) {
            return 8L;
        }

        @Override
        public void consumePasswordResetCode(Long tokenId, User user, String email) {
            resetEmail = email;
        }
    }

    private static class InMemoryUserRepository {
        private final Map<String, User> byUsername = new HashMap<>();
        private long nextId = 1L;

        UserRepository proxy() {
            return UserServiceTest.proxy(UserRepository.class, (proxy, method, args) -> switch (method.getName()) {
                case "findByUsername" -> findByUsername((String) args[0]);
                case "findByEmail" -> findByEmail((String) args[0], false);
                case "findByEmailAndEmailVerifiedAtIsNotNull" -> findByEmail((String) args[0], true);
                case "findById" -> byUsername.values().stream()
                        .filter(user -> user.getId().equals(args[0])).findFirst();
                case "findAll" -> new ArrayList<>(byUsername.values());
                case "save" -> save((User) args[0]);
                default -> defaultValue(method.getReturnType());
            });
        }

        Optional<User> findByUsername(String username) {
            return Optional.ofNullable(byUsername.get(username));
        }

        Optional<User> findByEmail(String email, boolean verifiedOnly) {
            return byUsername.values().stream()
                    .filter(user -> email.equals(user.getEmail()))
                    .filter(user -> !verifiedOnly || user.getEmailVerifiedAt() != null)
                    .findFirst();
        }

        User save(User user) {
            if (user.getId() == null) {
                user.setId(nextId++);
            }
            byUsername.put(user.getUsername(), user);
            return user;
        }
    }

    private static class InMemoryOrganizationTagRepository {
        private final Map<String, OrganizationTag> byTagId = new HashMap<>();

        OrganizationTagRepository proxy() {
            return UserServiceTest.proxy(OrganizationTagRepository.class, (proxy, method, args) -> switch (method.getName()) {
                case "existsByTagId" -> byTagId.containsKey((String) args[0]);
                case "findByTagId" -> findByTagId((String) args[0]);
                case "save" -> save((OrganizationTag) args[0]);
                default -> defaultValue(method.getReturnType());
            });
        }

        Optional<OrganizationTag> findByTagId(String tagId) {
            return Optional.ofNullable(byTagId.get(tagId));
        }

        OrganizationTag save(OrganizationTag tag) {
            byTagId.put(tag.getTagId(), tag);
            return tag;
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
