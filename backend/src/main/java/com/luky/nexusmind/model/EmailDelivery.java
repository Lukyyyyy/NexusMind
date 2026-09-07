package com.luky.nexusmind.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "email_deliveries", indexes = @Index(name = "idx_email_delivery_due", columnList = "status,next_attempt_at"))
public class EmailDelivery {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 320)
    private String recipient;
    @Column(nullable = false, length = 160)
    private String subject;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;
    @Enumerated(EnumType.STRING)
    @Column(name = "template_kind", nullable = false, length = 32)
    private TemplateKind templateKind = TemplateKind.TEST;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;
    @Column(nullable = false)
    private int attempts;
    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt = LocalDateTime.now();
    @Column(name = "last_error", length = 500)
    private String lastError;
    @CreationTimestamp
    private LocalDateTime createdAt;

    public enum Status { PENDING, SENT, FAILED }
    public enum TemplateKind {
        VERIFICATION, ORGANIZATION_APPLICATION, ORGANIZATION_RESULT,
        MEMBERSHIP_CHANGE, ROLE_CHANGE, ACCOUNT_STATUS_CHANGED, EMAIL_CHANGED, TEST
    }
}
