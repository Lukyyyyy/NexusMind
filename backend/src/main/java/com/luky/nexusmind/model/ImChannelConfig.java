package com.luky.nexusmind.model;

import com.luky.nexusmind.im.model.ImChannelType;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;

/** IM 渠道接入配置。credentials_cipher 为 AES-GCM 加密后的渠道凭据 JSON。 */
@Data
@Entity
@DynamicUpdate
@Table(name = "im_channel_config")
@IdClass(ImChannelConfig.CompositeKey.class)
public class ImChannelConfig {

    @Data
    public static class CompositeKey implements Serializable {
        private ImChannelType channelType;
        private Long userId;
    }

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "channel_type", nullable = false, length = 32)
    private ImChannelType channelType;

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 120)
    private String name;

    /** 渠道网关/开放平台基地址，例如 ClawBot 网关 http://host:port。 */
    @Column(name = "base_url", length = 300)
    private String baseUrl;

    @Column(name = "credentials_cipher", columnDefinition = "TEXT")
    private String credentialsCipher;

    @Column(name = "bot_id", length = 100)
    private String botId;

    @Column(nullable = false)
    private boolean enabled = true;

    /** 群聊消息是否要求 @机器人 才响应。 */
    @Column(name = "group_require_mention", nullable = false)
    private boolean groupRequireMention = false;

    /** 单条出站消息最大字符数，超出后分段发送。 */
    @Column(name = "max_outbound_chars", nullable = false)
    private int maxOutboundChars = 1600;

    /** 是否先发送“正在思考”占位再发正式回答。 */
    @Column(name = "placeholder_enabled", nullable = false)
    private boolean placeholderEnabled = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
