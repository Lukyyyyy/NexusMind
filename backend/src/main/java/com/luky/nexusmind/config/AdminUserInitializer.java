package com.luky.nexusmind.config;

import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.UserRepository;
import com.luky.nexusmind.utils.PasswordUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

/**
 * 管理员账号初始化器
 * 在应用启动时自动创建管理员账号（如果不存在）
 */
@Component
@Order(1) // 设置优先级，确保在其他初始化器之前运行
public class AdminUserInitializer implements CommandLineRunner {
    private static final Logger logger = LoggerFactory.getLogger(AdminUserInitializer.class);

    @Autowired
    private UserRepository userRepository;

    @Value("${admin.username:admin}")
    private String adminUsername;

    @Value("${admin.password:}")
    private String adminPassword;

    @Value("${admin.email:}")
    private String adminEmail;

    @Value("${admin.primary-org:default}")
    private String adminPrimaryOrg;

    @Value("${admin.org-tags:default,admin}")
    private String adminOrgTags;

    @Override
    public void run(String... args) throws Exception {
        requireStrongAdminPassword();
        logger.info("检查管理员账号是否存在: {}", adminUsername);
        Optional<User> existingAdmin = userRepository.findByUsername(adminUsername);

        if (existingAdmin.isPresent()) {
            // 已有账号不再自动提权或改资料，避免重启覆盖人工降权。
            return;
        }

        try {
            logger.info("开始创建管理员账号: {}", adminUsername);
            User adminUser = new User();
            adminUser.setUsername(adminUsername);
            adminUser.setDisplayName(adminUsername);
            adminUser.setPassword(PasswordUtil.encode(adminPassword));
            adminUser.setRole(User.Role.SUPER_ADMIN);
            adminUser.setPrimaryOrg(adminPrimaryOrg);
            adminUser.setOrgTags(adminOrgTags);
            if (!adminEmail.isBlank()) {
                adminUser.setEmail(adminEmail.trim().toLowerCase(Locale.ROOT));
                adminUser.setEmailVerifiedAt(LocalDateTime.now());
            }

            userRepository.save(adminUser);
            logger.info("管理员账号 '{}' 创建成功", adminUsername);
        } catch (Exception e) {
            logger.error("创建管理员账号失败: {}", e.getMessage(), e);
            throw new RuntimeException("无法创建管理员账号", e);
        }
    }

    private void requireStrongAdminPassword() {
        if (adminPassword == null || adminPassword.length() < 12
                || "admin123".equals(adminPassword) || "change-me".equals(adminPassword)) {
            throw new IllegalStateException("ADMIN_PASSWORD 未设置或过弱，拒绝启动");
        }
    }
}
