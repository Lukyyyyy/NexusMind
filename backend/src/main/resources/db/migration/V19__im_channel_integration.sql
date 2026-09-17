-- IM 渠道接入：渠道配置、用户绑定、会话映射、消息日志
CREATE TABLE IF NOT EXISTS im_channel_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    channel_type VARCHAR(32) NOT NULL,
    name VARCHAR(120) NOT NULL,
    base_url VARCHAR(300) NULL,
    credentials_cipher TEXT NULL,
    enabled BIT NOT NULL DEFAULT 1,
    group_require_mention BIT NOT NULL DEFAULT 0,
    max_outbound_chars INT NOT NULL DEFAULT 1600,
    placeholder_enabled BIT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_im_channel_type UNIQUE (channel_type)
);

CREATE TABLE IF NOT EXISTS im_user_binding (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    channel_type VARCHAR(32) NOT NULL,
    platform_user_id VARCHAR(160) NOT NULL,
    platform_union_id VARCHAR(160) NULL,
    platform_user_name VARCHAR(160) NULL,
    username VARCHAR(100) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    binding_code VARCHAR(16) NULL,
    code_expires_at DATETIME NULL,
    bound_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_im_binding_user UNIQUE (channel_type, platform_user_id),
    CONSTRAINT uk_im_binding_code UNIQUE (binding_code),
    KEY idx_im_binding_username (username)
);

CREATE TABLE IF NOT EXISTS im_conversation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    channel_type VARCHAR(32) NOT NULL,
    platform_conversation_id VARCHAR(200) NOT NULL,
    chat_session_id BIGINT NOT NULL,
    binding_id BIGINT NULL,
    bound_username VARCHAR(100) NULL,
    last_active_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_im_conversation UNIQUE (channel_type, platform_conversation_id)
);

CREATE TABLE IF NOT EXISTS im_message_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    channel_type VARCHAR(32) NOT NULL,
    direction VARCHAR(8) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'RECEIVED',
    platform_msg_id VARCHAR(200) NULL,
    platform_conversation_id VARCHAR(200) NULL,
    platform_user_id VARCHAR(160) NULL,
    bound_username VARCHAR(100) NULL,
    content TEXT NULL,
    error_msg VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_im_msg_platform UNIQUE (channel_type, direction, platform_msg_id),
    KEY idx_im_msg_conversation (channel_type, platform_conversation_id, created_at)
);
