package com.luky.nexusmind.service;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.model.OrganizationTag;
import com.luky.nexusmind.model.OrganizationMembership;
import com.luky.nexusmind.model.OrganizationJoinRequest;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.OrganizationTagRepository;
import com.luky.nexusmind.repository.UserRepository;
import com.luky.nexusmind.repository.FileUploadRepository;
import com.luky.nexusmind.repository.OrganizationMembershipRepository;
import com.luky.nexusmind.repository.OrganizationJoinRequestRepository;
import com.luky.nexusmind.utils.PasswordUtil;
import com.luky.nexusmind.utils.JwtUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;

/**
 * UserService 类用于处理用户注册和认证相关的业务逻辑。
 */
@Service
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);
    
    private static final String DEFAULT_ORG_TAG = "default";
    private static final String LEGACY_DEFAULT_ORG_TAG = "DEFAULT";
    private static final String DEFAULT_ORG_NAME = "默认组织";
    private static final String DEFAULT_ORG_DESCRIPTION = "系统默认组织标签，自动分配给所有新用户";
    private static final String PRIVATE_TAG_PREFIX = "PRIVATE_";
    private static final String PRIVATE_ORG_NAME_SUFFIX = "的私人空间";
    private static final String PRIVATE_ORG_DESCRIPTION = "用户的私人组织标签，仅用户本人可访问";
    private static final String USERNAME_PATTERN = "[A-Za-z0-9_-]{4,32}";
    private static final String USERNAME_INVALID_MESSAGE = "用户名需为4-32位英文字母、数字、下划线或短横线";
    private static final String PASSWORD_INVALID_MESSAGE = "密码需为8-72个字符，不能包含控制字符";
    private static final String ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private OrganizationTagRepository organizationTagRepository;
    
    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private OrganizationMembershipService organizationMembershipService;

    @Autowired
    private OrganizationMembershipRepository organizationMembershipRepository;

    @Autowired
    private OrganizationJoinRequestRepository organizationJoinRequestRepository;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private EmailVerificationService emailVerificationService;

    @Transactional
    public User registerVerifiedUser(String rawEmail, String code, String password) {
        String email = emailVerificationService.normalize(rawEmail);
        validatePassword(password);
        if (userRepository.findByEmail(email).isPresent()) {
            throw new CustomException("该邮箱已注册", HttpStatus.CONFLICT);
        }
        Long tokenId = emailVerificationService.verifyRegistrationCode(email, code);
        String username = generateUsername();
        registerUser(username, "用户_" + username.substring(username.length() - 6), password);
        User user = userRepository.findByUsername(username).orElseThrow();
        user.setEmail(email);
        user.setEmailVerifiedAt(LocalDateTime.now());
        emailVerificationService.consumeRegistrationCode(tokenId, email);
        return userRepository.save(user);
    }

    /**
     * 注册新用户。
     *
     * @param username 要注册的用户名
     * @param password 要注册的用户密码
     * @throws CustomException 如果用户名已存在，则抛出异常
     */
    @Transactional
    public void registerUser(String username, String password) {
        registerUser(username, username, password);
    }

    @Transactional
    public void registerUser(String username, String displayName, String password) {
        validateUsername(username);
        String normalizedDisplayName = validateDisplayName(displayName == null ? username : displayName);
        validatePassword(password);
        // 检查数据库中是否已存在该用户名
        if (userRepository.findByUsername(username).isPresent()) {
            // 若用户名已存在，抛出自定义异常，状态码为 400 Bad Request
            throw new CustomException("用户名已存在", HttpStatus.BAD_REQUEST);
        }
        
        // 确保默认组织标签存在（系统内部使用）
        ensureDefaultOrgTagExists();
        
        User user = new User();
        user.setUsername(username);
        user.setDisplayName(normalizedDisplayName);
        // 对密码进行加密处理并设置到 User 对象中
        user.setPassword(PasswordUtil.encode(password));
        // 设置用户角色为普通用户
        user.setRole(User.Role.USER);
        
        // 保存用户以生成ID
        userRepository.save(user);

        // 创建用户的私人组织标签
        String privateTagId = PRIVATE_TAG_PREFIX + username;
        createPrivateOrgTag(privateTagId, normalizedDisplayName, user);
        
        List<String> assignedOrgTags = List.of(DEFAULT_ORG_TAG, privateTagId);
        user.setOrgTags(String.join(",", assignedOrgTags));
        
        // 设置私人组织标签为主组织标签
        user.setPrimaryOrg(privateTagId);
        
        userRepository.save(user);

        if (organizationMembershipService != null) {
            organizationTagRepository.findByTagId(DEFAULT_ORG_TAG)
                    .ifPresent(tag -> organizationMembershipService.add(user, tag, OrganizationMembership.Source.SYSTEM));
            organizationTagRepository.findByTagId(privateTagId)
                    .ifPresent(tag -> organizationMembershipService.add(user, tag, OrganizationMembership.Source.SYSTEM));
        }
        
        // 缓存组织标签信息
        orgTagCacheService.cacheUserOrgTags(username, assignedOrgTags);
        orgTagCacheService.cacheUserPrimaryOrg(username, privateTagId);
        
        logger.info("User registered successfully with private organization tag: {}", username);
    }
    
    /**
     * 创建用户的私人组织标签
     */
    private void createPrivateOrgTag(String privateTagId, String displayName, User owner) {
        // 检查私人标签是否已存在
        if (!organizationTagRepository.existsByTagId(privateTagId)) {
            logger.info("Creating private organization tag for user: {}", owner.getUsername());
            
            // 创建私人组织标签
            OrganizationTag privateTag = new OrganizationTag();
            privateTag.setTagId(privateTagId);
            privateTag.setName(displayName + PRIVATE_ORG_NAME_SUFFIX);
            privateTag.setDescription(PRIVATE_ORG_DESCRIPTION);
            privateTag.setJoinable(false);
            privateTag.setCreatedBy(owner);
            
            organizationTagRepository.save(privateTag);
            logger.info("Private organization tag created successfully for user: {}", owner.getUsername());
        }
    }

    /**
     * 确保默认组织标签存在
     */
    private void ensureDefaultOrgTagExists() {
        if (!organizationTagRepository.existsByTagId(DEFAULT_ORG_TAG)) {
            logger.info("Creating default organization tag");
            
            // 寻找一个管理员用户作为创建者
            Optional<User> adminUser = userRepository.findAll().stream()
                    .filter(user -> user.getRole() != null && user.getRole().isAdministrator())
                    .findFirst();
            
            User creator;
            if (adminUser.isPresent()) {
                creator = adminUser.get();
            } else {
                // 如果没有管理员用户，则创建一个系统用户作为创建者
                creator = createSystemAdminIfNotExists();
            }
            
            // 创建默认组织标签
            OrganizationTag defaultTag = new OrganizationTag();
            defaultTag.setTagId(DEFAULT_ORG_TAG);
            defaultTag.setName(DEFAULT_ORG_NAME);
            defaultTag.setDescription(DEFAULT_ORG_DESCRIPTION);
            defaultTag.setJoinable(false);
            defaultTag.setCreatedBy(creator);
            
            organizationTagRepository.save(defaultTag);
            logger.info("Default organization tag created successfully");
        }
    }
    
    /**
     * 如果系统中没有管理员用户，则创建一个系统管理员
     */
    private User createSystemAdminIfNotExists() {
        String systemAdminUsername = "system_admin";
        
        return userRepository.findByUsername(systemAdminUsername)
                .orElseGet(() -> {
                    logger.info("Creating system admin user");
                    User systemAdmin = new User();
                    systemAdmin.setUsername(systemAdminUsername);
                    // 生成随机密码
                    String randomPassword = generateRandomPassword();
                    systemAdmin.setPassword(PasswordUtil.encode(randomPassword));
                    systemAdmin.setRole(User.Role.ADMIN);
                    
                    logger.info("System admin created");
                    return userRepository.save(systemAdmin);
                });
    }
    
    /**
     * 生成随机密码
     */
    private String generateRandomPassword() {
        // 生成16位随机密码
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            int index = (int) (Math.random() * chars.length());
            sb.append(chars.charAt(index));
        }
        return sb.toString();
    }

    /**
     * 创建管理员用户。
     *
     * @param username 要注册的管理员用户名
     * @param password 要注册的管理员密码
     * @param creatorUsername 创建者的用户名（必须是已存在的管理员）
     * @throws CustomException 如果用户名已存在或创建者不是管理员，则抛出异常
     */
    public void createAdminUser(String username, String password, String creatorUsername) {
        validatePassword(password);
        // 验证创建者是否为管理员
        User creator = userRepository.findByUsername(creatorUsername)
                .orElseThrow(() -> new CustomException("创建者不存在", HttpStatus.NOT_FOUND));
        
        if (creator.getRole() != User.Role.SUPER_ADMIN) {
            throw new CustomException("仅超级管理员可创建管理员账号", HttpStatus.FORBIDDEN);
        }
        
        // 检查数据库中是否已存在该用户名
        if (userRepository.findByUsername(username).isPresent()) {
            throw new CustomException("用户名已存在", HttpStatus.BAD_REQUEST);
        }
        
        User adminUser = new User();
        adminUser.setUsername(username);
        adminUser.setPassword(PasswordUtil.encode(password));
        adminUser.setRole(User.Role.ADMIN);
        userRepository.save(adminUser);
    }

    /**
     * 对用户进行认证。
     *
     * @param username 要认证的用户名
     * @param password 要认证的用户密码
     * @return 认证成功后返回用户的用户名
     * @throws CustomException 如果用户名或密码无效，则抛出异常
     */
    public String authenticateUser(String rawEmail, String password) {
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase(java.util.Locale.ROOT);
        User user = userRepository.findByEmailAndEmailVerifiedAtIsNotNull(email)
                .orElseThrow(() -> new CustomException("邮箱或密码错误", HttpStatus.UNAUTHORIZED));
        // 比较输入的密码和数据库中存储的加密密码是否匹配
        if (!PasswordUtil.matches(password, user.getPassword())) {
            // 若不匹配，抛出自定义异常，状态码为 401 Unauthorized
            throw new CustomException("邮箱或密码错误", HttpStatus.UNAUTHORIZED);
        }
        if (!user.isEnabled()) {
            throw new CustomException(JwtUtils.ACCOUNT_DISABLED_MESSAGE, HttpStatus.FORBIDDEN,
                    JwtUtils.ACCOUNT_DISABLED_CODE);
        }
        ensureDefaultOrgAssigned(user);
        // 认证成功，记录最后登录时间
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
        // 认证成功，返回用户的用户名
        return user.getUsername();
    }

    private void validatePassword(String password) {
        if (password == null || password.codePointCount(0, password.length()) < 8
                || password.codePointCount(0, password.length()) > 72
                || password.codePoints().anyMatch(Character::isISOControl)) {
            throw new CustomException(PASSWORD_INVALID_MESSAGE, HttpStatus.BAD_REQUEST);
        }
    }

    @Transactional
    public Long resetPassword(String rawEmail, String code, String password) {
        String email = emailVerificationService.normalize(rawEmail);
        validatePassword(password);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException("验证码错误或已过期", HttpStatus.BAD_REQUEST));
        Long tokenId = emailVerificationService.verifyPasswordResetCode(user, email, code);
        user.setPassword(PasswordUtil.encode(password));
        emailVerificationService.consumePasswordResetCode(tokenId, user, email);
        userRepository.save(user);
        return user.getId();
    }

    private String generateUsername() {
        String username;
        do {
            StringBuilder value = new StringBuilder("zs_");
            for (int i = 0; i < 10; i++) value.append(ID_CHARS.charAt(RANDOM.nextInt(ID_CHARS.length())));
            username = value.toString();
        } while (userRepository.findByUsername(username).isPresent());
        return username;
    }

    @Transactional
    public String updateDisplayName(String username, String displayName) {
        String value = validateDisplayName(displayName);
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
        user.setDisplayName(value);
        userRepository.save(user);
        organizationTagRepository.findByTagId(PRIVATE_TAG_PREFIX + username).ifPresent(tag -> {
            tag.setName(value + PRIVATE_ORG_NAME_SUFFIX);
            organizationTagRepository.save(tag);
        });
        return value;
    }

    private void validateUsername(String username) {
        if (username == null || !username.matches(USERNAME_PATTERN)) {
            throw new CustomException(USERNAME_INVALID_MESSAGE, HttpStatus.BAD_REQUEST);
        }
    }

    private String validateDisplayName(String displayName) {
        String value = displayName == null ? "" : displayName.trim();
        if (value.isEmpty() || value.codePointCount(0, value.length()) > 32 || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new CustomException("昵称需为1-32个字符，不能包含控制字符", HttpStatus.BAD_REQUEST);
        }
        return value;
    }
    
    /**
     * 创建组织标签
     * 
     * @param tagId 标签唯一标识
     * @param name 标签名称
     * @param description 标签描述
     * @param parentTag 父标签ID（可选）
     * @param creatorUsername 创建者用户名（必须是管理员）
     */
    @Transactional
    public OrganizationTag createOrganizationTag(String tagId, String name, String description, 
                                                String parentTag, String creatorUsername) {
        tagId = validateOrganizationTagId(tagId);
        // 验证创建者是否为管理员
        User creator = userRepository.findByUsername(creatorUsername)
                .orElseThrow(() -> new CustomException("创建者不存在", HttpStatus.NOT_FOUND));
        
        if (!creator.getRole().isAdministrator()) {
            throw new CustomException("仅管理员可创建组织标签", HttpStatus.FORBIDDEN);
        }
        
        // 检查标签ID是否已存在
        if (organizationTagRepository.existsByTagId(tagId)) {
            throw new CustomException("组织标签 ID 已存在", HttpStatus.BAD_REQUEST);
        }
        
        // 如果指定了父标签，检查父标签是否存在
        if (parentTag != null && !parentTag.isEmpty()) {
            organizationTagRepository.findByTagId(parentTag)
                    .orElseThrow(() -> new CustomException("父组织标签不存在", HttpStatus.NOT_FOUND));
        }
        if (organizationTagRepository.existsByNameAndParentTag(name.trim(), emptyToNull(parentTag))) {
            throw new CustomException("同一父组织下名称不能重复", HttpStatus.CONFLICT);
        }
        
        OrganizationTag tag = new OrganizationTag();
        tag.setTagId(tagId);
        tag.setName(name.trim());
        tag.setDescription(description);
        tag.setParentTag(parentTag);
        tag.setCreatedBy(creator);
        
        OrganizationTag savedTag = organizationTagRepository.save(tag);
        
        // 清除标签缓存，因为层级关系可能变化
        orgTagCacheService.invalidateAllEffectiveTagsCache();
        
        return savedTag;
    }
    
    /**
     * 为用户分配组织标签
     * 
     * @param userId 用户ID
     * @param orgTags 组织标签ID列表
     * @param adminUsername 管理员用户名
     */
    @Transactional
    public void assignOrgTagsToUser(Long userId, List<String> orgTags, String adminUsername) {
        // 验证操作者是否为管理员
        User admin = userRepository.findByUsername(adminUsername)
                .orElseThrow(() -> new CustomException("管理员不存在", HttpStatus.NOT_FOUND));
        
        if (!admin.getRole().isAdministrator()) {
            throw new CustomException("仅管理员可分配组织标签", HttpStatus.FORBIDDEN);
        }
        
        // 查找用户
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
        
        // 验证所有标签是否存在
        for (String tagId : orgTags) {
            if (!organizationTagRepository.existsByTagId(tagId)) {
                throw new CustomException("组织标签不存在：" + tagId, HttpStatus.NOT_FOUND);
            }
        }
        
        // 获取用户的现有组织标签
        Set<String> existingTags = new HashSet<>();
        if (user.getOrgTags() != null && !user.getOrgTags().isEmpty()) {
            existingTags = Arrays.stream(user.getOrgTags().split(",")).collect(Collectors.toSet());
        }
        
        // 找出并保留用户的私人组织标签
        String privateTagId = PRIVATE_TAG_PREFIX + user.getUsername();
        boolean hasPrivateTag = existingTags.contains(privateTagId);
        
        // 确保用户的私人组织标签不会被删除
        Set<String> finalTags = new HashSet<>(orgTags);
        if (hasPrivateTag && !finalTags.contains(privateTagId)) {
            finalTags.add(privateTagId);
        }
        
        // 将标签列表转换为逗号分隔的字符串
        String orgTagsStr = String.join(",", finalTags);
        user.setOrgTags(orgTagsStr);
        
        // 如果用户没有主组织标签且有组织标签，则优先使用私人标签作为主组织
        if ((user.getPrimaryOrg() == null || user.getPrimaryOrg().isEmpty()) && !finalTags.isEmpty()) {
            if (hasPrivateTag) {
                user.setPrimaryOrg(privateTagId);
            } else {
                user.setPrimaryOrg(new ArrayList<>(finalTags).get(0));
            }
        }
        
        userRepository.save(user);
        
        // 更新缓存
        orgTagCacheService.deleteUserOrgTagsCache(user.getUsername());
        orgTagCacheService.cacheUserOrgTags(user.getUsername(), new ArrayList<>(finalTags));
        // 同时清除有效标签缓存
        orgTagCacheService.deleteUserEffectiveTagsCache(user.getUsername());
        
        if (user.getPrimaryOrg() != null && !user.getPrimaryOrg().isEmpty()) {
            orgTagCacheService.cacheUserPrimaryOrg(user.getUsername(), user.getPrimaryOrg());
        }
    }
    
    /**
     * 获取用户的组织标签信息
     * 
     * @param username 用户名
     * @return 包含用户组织标签信息的Map
     */
    public Map<String, Object> getUserOrgTags(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
        ensureDefaultOrgAssigned(user);
        
        List<String> orgTags = organizationMembershipService == null
                ? Optional.ofNullable(orgTagCacheService.getUserOrgTags(username)).orElseGet(() -> parseOrgTags(user.getOrgTags()))
                    .stream().map(this::normalizeOrgTag).distinct().toList()
                : organizationMembershipService.direct(user).stream()
                    .map(value -> value.getOrganization().getTagId()).distinct().toList();
        String primaryOrg = orgTagCacheService.getUserPrimaryOrg(username);
        orgTagCacheService.cacheUserOrgTags(username, orgTags);
        
        if (primaryOrg == null || primaryOrg.isEmpty()) {
            primaryOrg = user.getPrimaryOrg();
        }
        primaryOrg = normalizeNullableOrgTag(primaryOrg);
        if (primaryOrg != null && !primaryOrg.isEmpty()) {
            // 更新缓存
            orgTagCacheService.cacheUserPrimaryOrg(username, primaryOrg);
        }
        
        // 获取组织标签的详细信息
        List<Map<String, String>> orgTagDetails = new ArrayList<>();
        for (String tagId : orgTags) {
            OrganizationTag tag = organizationTagRepository.findByTagId(tagId)
                    .orElse(null);
            if (tag != null) {
                Map<String, String> tagInfo = new HashMap<>();
                tagInfo.put("tagId", tag.getTagId());
                tagInfo.put("name", tag.getName());
                tagInfo.put("description", tag.getDescription());
                orgTagDetails.add(tagInfo);
            }
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("orgTags", orgTags);
        result.put("primaryOrg", primaryOrg);
        result.put("orgTagDetails", orgTagDetails);
        
        return result;
    }

    private void ensureDefaultOrgAssigned(User user) {
        ensureDefaultOrgTagExists();

        List<String> orgTags = parseOrgTags(user.getOrgTags());
        if (!orgTags.contains(DEFAULT_ORG_TAG)) {
            orgTags.add(0, DEFAULT_ORG_TAG);
        }

        String normalizedOrgTags = String.join(",", orgTags);
        String normalizedPrimaryOrg = normalizeNullableOrgTag(user.getPrimaryOrg());
        boolean unchanged = normalizedOrgTags.equals(user.getOrgTags())
                && normalizedPrimaryOrg != null
                && normalizedPrimaryOrg.equals(user.getPrimaryOrg())
                && !normalizedPrimaryOrg.isEmpty();
        if (unchanged) {
            return;
        }

        user.setOrgTags(normalizedOrgTags);

        if (normalizedPrimaryOrg == null || normalizedPrimaryOrg.isEmpty()) {
            user.setPrimaryOrg(DEFAULT_ORG_TAG);
        } else {
            user.setPrimaryOrg(normalizedPrimaryOrg);
        }

        userRepository.save(user);
        orgTagCacheService.cacheUserOrgTags(user.getUsername(), orgTags);
        if (user.getPrimaryOrg() != null && !user.getPrimaryOrg().isEmpty()) {
            orgTagCacheService.cacheUserPrimaryOrg(user.getUsername(), user.getPrimaryOrg());
        }
        orgTagCacheService.deleteUserEffectiveTagsCache(user.getUsername());
    }

    private List<String> parseOrgTags(String orgTags) {
        if (orgTags == null || orgTags.isBlank()) {
            return new ArrayList<>();
        }

        return normalizeOrgTags(Arrays.asList(orgTags.split(",")));
    }

    private List<String> normalizeOrgTags(List<String> orgTags) {
        return orgTags.stream()
                .map(String::trim)
                .map(this::normalizeOrgTag)
                .filter(tag -> !tag.isEmpty())
                .distinct()
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private String normalizeOrgTag(String tagId) {
        return LEGACY_DEFAULT_ORG_TAG.equals(tagId) || DEFAULT_ORG_NAME.equals(tagId) ? DEFAULT_ORG_TAG : tagId;
    }

    private String normalizeNullableOrgTag(String tagId) {
        return tagId == null ? null : normalizeOrgTag(tagId.trim());
    }
    
    /**
     * 设置用户的主组织标签
     * 
     * @param username 用户名
     * @param primaryOrg 主组织标签
     */
    public void setUserPrimaryOrg(String username, String primaryOrg) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
        String normalizedPrimaryOrg = normalizeNullableOrgTag(primaryOrg);
        
        // 检查该组织标签是否已分配给用户
        OrganizationTag selected = organizationTagRepository.findByTagId(normalizedPrimaryOrg)
                .orElseThrow(() -> new CustomException("组织标签不存在", HttpStatus.NOT_FOUND));
        if (DEFAULT_ORG_TAG.equals(normalizedPrimaryOrg) || selected.getArchivedAt() != null
                || !organizationMembershipService.directMember(user, normalizedPrimaryOrg)) {
            throw new CustomException("该用户未分配此组织标签", HttpStatus.BAD_REQUEST);
        }
        
        user.setPrimaryOrg(normalizedPrimaryOrg);
        userRepository.save(user);
        
        // 更新缓存
        orgTagCacheService.cacheUserPrimaryOrg(username, normalizedPrimaryOrg);
    }
    
    /**
     * 获取用户的主组织标签
     * 
     * @param userId 用户ID
     * @return 用户的主组织标签
     */
    public String getUserPrimaryOrg(String userId) {
        // 先通过userId查找用户，然后获取username
        User user;
        try {
            Long userIdLong = Long.parseLong(userId);
            user = userRepository.findById(userIdLong)
                .orElseThrow(() -> new CustomException("用户不存在，ID：" + userId, HttpStatus.NOT_FOUND));
        } catch (NumberFormatException e) {
            // 如果userId不是数字格式，则假设它就是username
            user = userRepository.findByUsername(userId)
                .orElseThrow(() -> new CustomException("用户不存在：" + userId, HttpStatus.NOT_FOUND));
        }
        
        String username = user.getUsername();
        
        // 尝试从缓存获取
        String primaryOrg = orgTagCacheService.getUserPrimaryOrg(username);
        primaryOrg = normalizeNullableOrgTag(primaryOrg);
        
        // 如果缓存中没有，则从数据库获取
        if (primaryOrg == null || primaryOrg.isEmpty()) {
            primaryOrg = normalizeNullableOrgTag(user.getPrimaryOrg());
            
            // 如果用户没有设置主组织标签，则尝试使用第一个分配的组织标签
            if (primaryOrg == null || primaryOrg.isEmpty()) {
                List<String> tags = parseOrgTags(user.getOrgTags());
                if (!tags.isEmpty()) {
                    primaryOrg = tags.get(0);
                    // 更新用户的主组织标签
                    user.setPrimaryOrg(primaryOrg);
                    userRepository.save(user);
                } else {
                    // 如果用户没有任何组织标签，则使用默认标签
                    primaryOrg = DEFAULT_ORG_TAG;
                }
            }
            
            // 更新缓存
            orgTagCacheService.cacheUserPrimaryOrg(username, primaryOrg);
        } else if (!primaryOrg.equals(user.getPrimaryOrg())) {
            user.setPrimaryOrg(primaryOrg);
            userRepository.save(user);
            orgTagCacheService.cacheUserPrimaryOrg(username, primaryOrg);
        }
        
        return primaryOrg;
    }

    /**
     * 获取组织标签树结构
     * 
     * @return 组织标签树结构
     */
    public List<Map<String, Object>> getOrganizationTagTree() {
        // 获取所有根节点（parentTag为null的标签）
        List<OrganizationTag> rootTags = organizationTagRepository.findByParentTag(null).stream()
                .filter(tag -> !tag.getTagId().startsWith(PRIVATE_TAG_PREFIX))
                .toList();
        
        // 递归构建标签树
        return buildTagTreeRecursive(rootTags);
    }
    
    /**
     * 递归构建标签树
     * 
     * @param tags 当前级别的标签列表
     * @return 树形结构
     */
    private List<Map<String, Object>> buildTagTreeRecursive(List<OrganizationTag> tags) {
        List<Map<String, Object>> result = new ArrayList<>();
        
        for (OrganizationTag tag : tags) {
            Map<String, Object> node = new HashMap<>();
            node.put("tagId", tag.getTagId());
            node.put("name", tag.getName());
            node.put("description", tag.getDescription());
            node.put("parentTag", tag.getParentTag()); // 添加父标签字段
            node.put("joinable", tag.isJoinable());
            node.put("archivedAt", tag.getArchivedAt());
            node.put("archiveReason", tag.getArchiveReason());
            
            // 获取子标签
            List<OrganizationTag> children = organizationTagRepository.findByParentTag(tag.getTagId());
            if (!children.isEmpty()) {
                node.put("children", buildTagTreeRecursive(children));
            }
            // 如果没有子节点，不添加children字段，而不是添加空数组
            
            result.add(node);
        }
        
        return result;
    }
    
    /**
     * 更新组织标签
     * 
     * @param tagId 标签ID
     * @param name 新名称
     * @param description 新描述
     * @param parentTag 新父标签ID
     * @param adminUsername 管理员用户名
     * @return 更新后的组织标签
     */
    @Transactional
    public OrganizationTag updateOrganizationTag(String tagId, String newTagId, String name, String description,
                                                String parentTag, String adminUsername) {
        // 验证操作者是否为管理员
        User admin = userRepository.findByUsername(adminUsername)
                .orElseThrow(() -> new CustomException("管理员不存在", HttpStatus.NOT_FOUND));
        
        if (!admin.getRole().isAdministrator()) {
            throw new CustomException("仅管理员可更新组织标签", HttpStatus.FORBIDDEN);
        }
        
        // 获取要更新的标签
        OrganizationTag tag = organizationTagRepository.findByTagId(tagId)
                .orElseThrow(() -> new CustomException("组织标签不存在", HttpStatus.NOT_FOUND));
        if (DEFAULT_ORG_TAG.equals(tagId) || "admin".equals(tagId) || tagId.startsWith(PRIVATE_TAG_PREFIX)) {
            throw new CustomException("系统组织不可编辑", HttpStatus.FORBIDDEN);
        }
        String normalizedTagId = newTagId == null ? tagId : validateOrganizationTagId(newTagId);
        boolean rename = !tagId.equals(normalizedTagId);
        if (rename && organizationTagRepository.existsByTagId(normalizedTagId)) {
            throw new CustomException("组织标签 ID 已存在", HttpStatus.CONFLICT);
        }
        // ponytail: 文档权限还写入 ES/Neo4j；只有完成跨存储迁移后才放开有文档组织的 ID 修改。
        if (rename && fileUploadRepository.countByOrgTag(tagId) > 0) {
            throw new CustomException("该组织已有文档，暂不能修改标签ID", HttpStatus.CONFLICT);
        }
        String normalizedParent = emptyToNull(parentTag);
        if (!Objects.equals(tag.getParentTag(), normalizedParent)
                && (organizationMembershipRepository.countByOrganizationTagId(tagId) > 0
                || organizationJoinRequestRepository.existsByOrganizationTagId(tagId)
                || fileUploadRepository.countByOrgTag(tagId) > 0)) {
            throw new CustomException("已有业务数据的组织不能调整父级", HttpStatus.CONFLICT);
        }
        if (name != null && !name.isBlank() && !name.trim().equals(tag.getName())
                && organizationTagRepository.existsByNameAndParentTag(name.trim(), normalizedParent)) {
            throw new CustomException("同一父组织下名称不能重复", HttpStatus.CONFLICT);
        }
        
        // 如果指定了父标签，检查父标签是否存在
        if (parentTag != null && !parentTag.isEmpty()) {
            // 检查是否为自身
            if (tagId.equals(parentTag) || normalizedTagId.equals(parentTag)) {
                throw new CustomException("组织标签不能以自身作为父级", HttpStatus.BAD_REQUEST);
            }
            
            // 检查是否存在
            organizationTagRepository.findByTagId(parentTag)
                    .orElseThrow(() -> new CustomException("父组织标签不存在", HttpStatus.NOT_FOUND));
            
            // 检查是否会形成循环
            if (wouldFormCycle(tagId, parentTag)) {
                throw new CustomException("设置该父级会导致组织标签层级循环", HttpStatus.BAD_REQUEST);
            }
        }
        
        if (rename) {
            OrganizationTag replacement = new OrganizationTag();
            replacement.setTagId(normalizedTagId);
            replacement.setName(tag.getName());
            replacement.setDescription(tag.getDescription());
            replacement.setParentTag(tag.getParentTag());
            replacement.setJoinable(tag.isJoinable());
            replacement.setArchivedAt(tag.getArchivedAt());
            replacement.setArchiveReason(tag.getArchiveReason());
            replacement.setCreatedBy(tag.getCreatedBy());
            replacement.setCreatedAt(tag.getCreatedAt());
            replacement.setUpdatedAt(tag.getUpdatedAt());
            replacement = organizationTagRepository.saveAndFlush(replacement);

            List<OrganizationTag> children = organizationTagRepository.findByParentTag(tagId);
            children.forEach(child -> child.setParentTag(normalizedTagId));
            organizationTagRepository.saveAll(children);

            List<OrganizationMembership> memberships = organizationMembershipRepository.findByOrganizationTagId(tagId);
            OrganizationTag renamedTag = replacement;
            memberships.forEach(membership -> membership.setOrganization(renamedTag));
            organizationMembershipRepository.saveAll(memberships);

            List<OrganizationJoinRequest> requests = organizationJoinRequestRepository.findByOrganizationTagId(tagId);
            requests.forEach(request -> request.setOrganization(renamedTag));
            organizationJoinRequestRepository.saveAll(requests);

            for (User user : userRepository.findAll()) {
                List<String> orgTags = parseOrgTags(user.getOrgTags());
                boolean changed = orgTags.contains(tagId);
                orgTags.replaceAll(value -> value.equals(tagId) ? normalizedTagId : value);
                if (changed || tagId.equals(user.getPrimaryOrg())) {
                    user.setOrgTags(String.join(",", orgTags));
                    if (tagId.equals(user.getPrimaryOrg())) user.setPrimaryOrg(normalizedTagId);
                    userRepository.save(user);
                    orgTagCacheService.deleteUserOrgTagsCache(user.getUsername());
                }
            }
            entityManager.createQuery("update AuditEvent event set event.targetOrgTag = :newId where event.targetOrgTag = :oldId")
                    .setParameter("newId", normalizedTagId).setParameter("oldId", tagId).executeUpdate();
            organizationTagRepository.delete(tag);
            tag = replacement;
        }

        // 更新标签
        if (name != null && !name.isEmpty()) {
            tag.setName(name.trim());
        }
        
        if (description != null) {
            tag.setDescription(description);
        }
        
        tag.setParentTag(normalizedParent);
        
        OrganizationTag updatedTag = organizationTagRepository.save(tag);
        
        // 清除所有标签缓存，因为层级关系可能变化
        orgTagCacheService.invalidateAllEffectiveTagsCache();
        
        return updatedTag;
    }

    private String validateOrganizationTagId(String tagId) {
        String value = tagId == null ? "" : tagId.trim();
        if (value.isEmpty() || value.length() > 60 || value.codePoints().anyMatch(Character::isISOControl)
                || value.matches(".*[/\\\\?#].*")) {
            throw new CustomException("标签ID需为1-60个字符，且不能包含 /\\?# 或控制字符", HttpStatus.BAD_REQUEST);
        }
        if (value.startsWith(PRIVATE_TAG_PREFIX)) {
            throw new CustomException("标签ID不能以PRIVATE_开头", HttpStatus.BAD_REQUEST);
        }
        return value;
    }
    
    /**
     * 检查是否会形成标签层级循环
     * 
     * @param tagId 要设置父标签的标签ID
     * @param newParentId 新的父标签ID
     * @return 是否会形成循环
     */
    private boolean wouldFormCycle(String tagId, String newParentId) {
        String currentParentId = newParentId;
        
        // 检查是否形成循环
        while (currentParentId != null && !currentParentId.isEmpty()) {
            if (tagId.equals(currentParentId)) {
                return true; // 形成循环
            }
            
            // 获取父标签的父标签
            Optional<OrganizationTag> parentTag = organizationTagRepository.findByTagId(currentParentId);
            if (parentTag.isEmpty()) {
                break;
            }
            
            currentParentId = parentTag.get().getParentTag();
        }
        
        return false;
    }
    
    /**
     * 删除组织标签
     * 
     * @param tagId 标签ID
     * @param adminUsername 管理员用户名
     */
    @Transactional
    public void deleteOrganizationTag(String tagId, String adminUsername) {
        // 验证操作者是否为管理员
        User admin = userRepository.findByUsername(adminUsername)
                .orElseThrow(() -> new CustomException("管理员不存在", HttpStatus.NOT_FOUND));
        
        if (!admin.getRole().isAdministrator()) {
            throw new CustomException("仅管理员可删除组织标签", HttpStatus.FORBIDDEN);
        }
        
        // 获取要删除的标签
        OrganizationTag tag = organizationTagRepository.findByTagId(tagId)
                .orElseThrow(() -> new CustomException("组织标签不存在", HttpStatus.NOT_FOUND));
        if (DEFAULT_ORG_TAG.equals(tagId) || "admin".equals(tagId) || tagId.startsWith(PRIVATE_TAG_PREFIX)) {
            throw new CustomException("系统组织不可删除", HttpStatus.FORBIDDEN);
        }
        
        // 检查是否是特殊标签（如默认标签）
        if (DEFAULT_ORG_TAG.equals(tagId)) {
            throw new CustomException("不能删除默认组织标签", HttpStatus.BAD_REQUEST);
        }
        
        // 检查是否有子标签
        List<OrganizationTag> children = organizationTagRepository.findByParentTag(tagId);
        if (!children.isEmpty()) {
            throw new CustomException("不能删除包含子标签的组织标签", HttpStatus.BAD_REQUEST);
        }
        
        // 检查是否有用户使用此标签
        List<User> users = userRepository.findAll();
        for (User user : users) {
            if (user.getOrgTags() != null && !user.getOrgTags().isEmpty()) {
                Set<String> userTags = new HashSet<>(Arrays.asList(user.getOrgTags().split(",")));
                if (userTags.contains(tagId)) {
                    throw new CustomException("不能删除已分配给用户的组织标签", HttpStatus.CONFLICT);
                }
                
                // 检查是否被用作主组织标签
                if (tagId.equals(user.getPrimaryOrg())) {
                    throw new CustomException("不能删除正被用作主组织的标签", HttpStatus.CONFLICT);
                }
            }
        }
        
        if (fileUploadRepository.countByOrgTag(tagId) > 0 || organizationJoinRequestRepository.existsByOrganizationTagId(tagId)) {
            throw new CustomException("已有业务数据的组织只能归档", HttpStatus.CONFLICT);
        }
        
        // 删除标签
        organizationTagRepository.delete(tag);
        
        // 清除所有标签缓存，因为层级关系可能变化
        orgTagCacheService.invalidateAllEffectiveTagsCache();
        
        logger.info("Organization tag deleted successfully: {}", tagId);
    }
    
    /**
     * 获取用户列表，支持分页和过滤
     * 
     * @param keyword 搜索关键词
     * @param orgTag 组织标签过滤
     * @param status 用户状态过滤
     * @param page 页码
     * @param size 每页大小
     * @return 用户列表数据
     */
    /** 用户列表排序字段白名单：前端字段名 -> 实体属性名 */
    private static final Map<String, String> USER_LIST_SORT_FIELDS = Map.of(
            "createTime", "createdAt",
            "lastLoginTime", "lastLoginAt"
    );

    /**
     * 解析用户列表的排序规则，仅支持白名单字段，默认按创建时间升序
     */
    private Sort resolveUserListSort(String sortField, String sortOrder) {
        // Map.of 创建的不可变 Map 不允许 null key，需先判空
        String property = sortField == null ? null : USER_LIST_SORT_FIELDS.get(sortField);
        if (property == null) {
            return Sort.by(Sort.Direction.ASC, "createdAt");
        }
        Sort.Direction direction = "desc".equalsIgnoreCase(sortOrder) ? Sort.Direction.DESC : Sort.Direction.ASC;
        return Sort.by(direction, property);
    }

    /** 内存过滤路径下与数据库排序保持一致的比较器（升序 null 在前，降序 null 在后） */
    private Comparator<User> resolveUserListComparator(String sortField, String sortOrder) {
        boolean byLastLogin = "lastLoginTime".equals(sortField);
        Comparator<User> comparator = byLastLogin
                ? Comparator.comparing(User::getLastLoginAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                : Comparator.comparing(User::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()));
        return "desc".equalsIgnoreCase(sortOrder) ? comparator.reversed() : comparator;
    }

    public Map<String, Object> getUserList(String keyword, String orgTag, Integer status, int page, int size, boolean revealEmail,
                                           String sortField, String sortOrder) {
        if (status != null && status != 0 && status != 1) {
            throw new CustomException("启用状态无效", HttpStatus.BAD_REQUEST);
        }
        // 页码从1开始，需要转换为从0开始
        int pageIndex = page > 0 ? page - 1 : 0;
        // 创建分页请求
        Pageable pageable = PageRequest.of(pageIndex, size, resolveUserListSort(sortField, sortOrder));
        
        List<User> filteredUsers = userRepository.findAll().stream()
                .filter(user -> orgTag == null || orgTag.isEmpty() || user.getOrgTags() != null
                        && Arrays.asList(user.getOrgTags().split(",")).contains(orgTag))
                .filter(user -> keyword == null || keyword.isEmpty() || user.getUsername().contains(keyword)
                        || Objects.toString(user.getDisplayName(), "").contains(keyword))
                .filter(user -> status == null || user.isEnabled() == (status == 1))
                .sorted(resolveUserListComparator(sortField, sortOrder))
                .toList();
        int start = Math.min((int) pageable.getOffset(), filteredUsers.size());
        int end = Math.min(start + pageable.getPageSize(), filteredUsers.size());
        // ponytail: 用户列表已有组织标签内存过滤；用户量显著增长时再统一下沉为数据库查询。
        Page<User> userPage = new PageImpl<>(filteredUsers.subList(start, end), pageable, filteredUsers.size());
        
        // 转换为前端需要的格式
        List<Map<String, Object>> userList = userPage.getContent().stream()
                .map(user -> {
                    Map<String, Object> userMap = new HashMap<>();
                    userMap.put("userId", user.getId());
                    userMap.put("username", user.getUsername());
                    userMap.put("displayName", user.getDisplayName() == null ? user.getUsername() : user.getDisplayName());
                    
                    // 获取用户组织标签的详细信息
                    List<Map<String, String>> orgTagDetails = organizationMembershipService.direct(user).stream()
                            .map(membership -> {
                                Map<String, String> tagInfo = new HashMap<>();
                                tagInfo.put("tagId", membership.getOrganization().getTagId());
                                tagInfo.put("name", membership.getOrganization().getName());
                                return tagInfo;
                            }).toList();
                    
                    userMap.put("orgTags", orgTagDetails);
                    userMap.put("primaryOrg", user.getPrimaryOrg());
                    userMap.put("status", user.isEnabled() ? 1 : 0);
                    userMap.put("role", user.getRole());
                    userMap.put("email", user.getEmailVerifiedAt() == null ? "" : revealEmail ? user.getEmail() : maskEmail(user.getEmail()));
                    userMap.put("emailVerified", user.getEmailVerifiedAt() != null);
                    userMap.put("createdAt", user.getCreatedAt());
                    // 前端用户列表读取 createTime / lastLoginTime 字段
                    userMap.put("createTime", user.getCreatedAt());
                    userMap.put("lastLoginTime", user.getLastLoginAt());
                    
                    return userMap;
                })
                .collect(Collectors.toList());
        
        // 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("content", userList);
        result.put("totalElements", userPage.getTotalElements());
        result.put("totalPages", userPage.getTotalPages());
        result.put("size", userPage.getSize());
        result.put("number", userPage.getNumber() + 1); // 转换为从1开始的页码
        
        return result;
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "";
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        return local.substring(0, Math.min(2, local.length())) + "***" + email.substring(at);
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
