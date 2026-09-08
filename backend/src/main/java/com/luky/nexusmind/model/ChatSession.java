package com.luky.nexusmind.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@DynamicUpdate
@Table(name = "chat_sessions", indexes = {
        @Index(name = "idx_chat_session_user_updated", columnList = "user_id,updated_at"),
        @Index(name = "idx_chat_session_deleted", columnList = "deleted_at")
})
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(name = "title_generated", nullable = false)
    private boolean titleGenerated;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", length = 20)
    private ChatScopeType scopeType = ChatScopeType.ALL;

    /** Organization tag or comma-separated FileUpload ids. */
    @Column(name = "scope_value", columnDefinition = "TEXT")
    private String scopeValue;

    @Column(name = "scope_label", length = 160)
    private String scopeLabel = "全部知识";

    /** Newline-separated display names retained for audit after a document is removed. */
    @Column(name = "scope_details", columnDefinition = "TEXT")
    private String scopeDetails;

    @Column(name = "llm_config_id")
    private Long llmConfigId;

    /** Snapshot retained when the underlying model configuration is later renamed or removed. */
    @Column(name = "llm_model_name", length = 160)
    private String llmModelName;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
