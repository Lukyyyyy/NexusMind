package com.luky.nexusmind.model;

import com.luky.nexusmind.im.model.ImChannelType;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/** 平台会话与 NexusMind 聊天会话的映射，IM 问答历史统一沉淀到 chat_sessions。 */
@Data
@Entity
@Table(name = "im_conversation",
        uniqueConstraints = @UniqueConstraint(name = "uk_im_conversation", columnNames = {"channel_type", "user_id", "platform_conversation_id"}),
        indexes = {@Index(name = "idx_im_conversation_user", columnList = "user_id")})
public class ImConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_type", nullable = false, length = 32)
    private ImChannelType channelType;

    /** 平台会话 id：单聊即对方用户 id，群聊即群 id。 */
    @Column(name = "platform_conversation_id", nullable = false, length = 200)
    private String platformConversationId;

    @Column(name = "chat_session_id", nullable = false)
    private Long chatSessionId;

    @Column(name = "last_active_at", nullable = false)
    private LocalDateTime lastActiveAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
