package com.luky.nexusmind.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "audit_events", indexes = {
        @Index(name = "idx_audit_actor", columnList = "actor_id,created_at"),
        @Index(name = "idx_audit_target_user", columnList = "target_user_id,created_at"),
        @Index(name = "idx_audit_target_org", columnList = "target_org_tag,created_at")
})
public class AuditEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private User actor;

    @Column(name = "actor_username", nullable = false, length = 255)
    private String actorUsername;

    @Column(name = "actor_role", nullable = false, length = 24)
    private String actorRole;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(name = "target_user_id")
    private Long targetUserId;

    @Column(name = "target_org_tag", length = 255)
    private String targetOrgTag;

    @Column(length = 1000)
    private String reason;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
