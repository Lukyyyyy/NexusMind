package com.luky.nexusmind.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(columnNames = "username"))
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "display_name", nullable = false, length = 32)
    private String displayName;

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "org_tags")
    private String orgTags; // 用户所属组织标签，多个用逗号分隔

    @Column(name = "primary_org")
    private String primaryOrg; // 用户主组织标签

    @Column(length = 320, unique = true)
    private String email;

    @Column(name = "email_verified_at")
    private LocalDateTime emailVerifiedAt;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "organization_email_enabled", nullable = false)
    private boolean organizationEmailEnabled = true;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "session_version", nullable = false)
    private long sessionVersion;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @PrePersist
    void defaultDisplayName() {
        if (displayName == null || displayName.isBlank()) {
            displayName = username;
        }
    }

    public enum Role {
        USER(false), ADMIN(true), SUPER_ADMIN(true);

        private final boolean administrator;

        Role(boolean administrator) {
            this.administrator = administrator;
        }

        public boolean isAdministrator() {
            return administrator;
        }
    }
}
