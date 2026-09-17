package com.luky.nexusmind.model;

import com.luky.nexusmind.im.model.ImChannelType;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/** IM 收发消息日志：审计 + 幂等去重（入站以 platform_msg_id 唯一）。 */
@Data
@Entity
@Table(name = "im_message_log",
        uniqueConstraints = @UniqueConstraint(name = "uk_im_msg_platform", columnNames = {"channel_type", "direction", "platform_msg_id"}),
        indexes = {@Index(name = "idx_im_msg_conversation", columnList = "channel_type,user_id,platform_conversation_id,created_at"),
                   @Index(name = "idx_im_msg_user", columnList = "user_id")})
public class ImMessageLog {

    public enum Direction { IN, OUT }

    public enum Status { RECEIVED, DISPATCHED, SENT, FAILED, DUPLICATE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_type", nullable = false, length = 32)
    private ImChannelType channelType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.RECEIVED;

    @Column(name = "platform_msg_id", length = 200)
    private String platformMsgId;

    @Column(name = "platform_conversation_id", length = 200)
    private String platformConversationId;

    @Column(name = "platform_user_id", length = 160)
    private String platformUserId;

    @Column(name = "bound_username", length = 100)
    private String boundUsername;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "error_msg", length = 500)
    private String errorMsg;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
