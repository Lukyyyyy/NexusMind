-- 每用户独立机器人（替代已删除的旧 V20 文件）。
--
-- 背景：旧 V20__im_per_user_channel.sql 在部分环境执行失败，留下 success=0 的
-- Flyway 记录并阻塞后续迁移；同时实体已改为 (channel_type, user_id) 复合主键。
-- 本脚本幂等：清理失败记录，补齐 user_id/bot_id 列，切换复合主键，删除绑定表，
-- 会话与消息日志关联 user_id。三张表当时均为空，可安全重建。

-- 0. 清理失败的旧 V20 记录（若存在）
DELETE FROM flyway_schema_history WHERE version = '20' AND success = 0;

-- 1. im_channel_config：补齐 user_id / bot_id 列
SET @has_user_id := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_channel_config' AND COLUMN_NAME = 'user_id');
SET @add_user_id := IF(@has_user_id = 0,
    'ALTER TABLE im_channel_config ADD COLUMN user_id BIGINT NULL AFTER channel_type', 'SELECT 1');
PREPARE stmt_add_user FROM @add_user_id; EXECUTE stmt_add_user; DEALLOCATE PREPARE stmt_add_user;

-- 旧失败脚本残留的 user_id 为 NOT NULL DEFAULT 0，先放宽以便归整
SET @col_nullable := (SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_channel_config' AND COLUMN_NAME = 'user_id');
SET @user_id_in_pk := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_channel_config'
      AND INDEX_NAME = 'PRIMARY' AND COLUMN_NAME = 'user_id');
SET @relax_user := IF(@col_nullable = 'NO' AND @user_id_in_pk = 0,
    'ALTER TABLE im_channel_config MODIFY COLUMN user_id BIGINT NULL', 'SELECT 1');
PREPARE stmt_relax FROM @relax_user; EXECUTE stmt_relax; DEALLOCATE PREPARE stmt_relax;

SET @has_bot_id := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_channel_config' AND COLUMN_NAME = 'bot_id');
SET @add_bot_id := IF(@has_bot_id = 0,
    'ALTER TABLE im_channel_config ADD COLUMN bot_id VARCHAR(100) NULL AFTER base_url', 'SELECT 1');
PREPARE stmt_add_bot FROM @add_bot_id; EXECUTE stmt_add_bot; DEALLOCATE PREPARE stmt_add_bot;

-- 历史数据归属超级管理员（id=1）；线上三张表均为空，此为兜底
UPDATE im_channel_config SET user_id = 1 WHERE user_id IS NULL OR user_id = 0;

-- 主键改为 (channel_type, user_id)
SET @pk_is_composite := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_channel_config'
      AND INDEX_NAME = 'PRIMARY' AND COLUMN_NAME = 'user_id');
SET @fix_pk := IF(@pk_is_composite = 0,
    'ALTER TABLE im_channel_config DROP PRIMARY KEY, DROP COLUMN id, ADD PRIMARY KEY (channel_type, user_id)',
    'SELECT 1');
PREPARE stmt_fix_pk FROM @fix_pk; EXECUTE stmt_fix_pk; DEALLOCATE PREPARE stmt_fix_pk;

-- 删除旧的 channel_type 全局唯一约束（复合主键已保证按用户唯一）
SET @has_old_uk := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_channel_config'
      AND INDEX_NAME = 'uk_im_channel_type');
SET @drop_old_uk := IF(@has_old_uk > 0,
    'ALTER TABLE im_channel_config DROP INDEX uk_im_channel_type', 'SELECT 1');
PREPARE stmt_drop_old_uk FROM @drop_old_uk; EXECUTE stmt_drop_old_uk; DEALLOCATE PREPARE stmt_drop_old_uk;

SET @has_user_idx := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_channel_config'
      AND INDEX_NAME = 'idx_im_channel_user');
SET @add_user_idx := IF(@has_user_idx = 0,
    'ALTER TABLE im_channel_config ADD INDEX idx_im_channel_user (user_id)', 'SELECT 1');
PREPARE stmt_add_user_idx FROM @add_user_idx; EXECUTE stmt_add_user_idx; DEALLOCATE PREPARE stmt_add_user_idx;

-- 2. 删除绑定码流程表（每用户独立机器人不再需要绑定）
DROP TABLE IF EXISTS im_user_binding;

-- 3. im_conversation：关联 user_id，删除 binding 相关列
SET @conv_has_user := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_conversation' AND COLUMN_NAME = 'user_id');
SET @conv_add_user := IF(@conv_has_user = 0,
    'ALTER TABLE im_conversation ADD COLUMN user_id BIGINT NULL AFTER id', 'SELECT 1');
PREPARE stmt_conv_add FROM @conv_add_user; EXECUTE stmt_conv_add; DEALLOCATE PREPARE stmt_conv_add;

UPDATE im_conversation SET user_id = 1 WHERE user_id IS NULL;

SET @conv_has_binding := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_conversation' AND COLUMN_NAME = 'binding_id');
SET @conv_drop_binding := IF(@conv_has_binding > 0,
    'ALTER TABLE im_conversation DROP COLUMN binding_id', 'SELECT 1');
PREPARE stmt_conv_drop_b FROM @conv_drop_binding; EXECUTE stmt_conv_drop_b; DEALLOCATE PREPARE stmt_conv_drop_b;

SET @conv_has_bound := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_conversation' AND COLUMN_NAME = 'bound_username');
SET @conv_drop_bound := IF(@conv_has_bound > 0,
    'ALTER TABLE im_conversation DROP COLUMN bound_username', 'SELECT 1');
PREPARE stmt_conv_drop_u FROM @conv_drop_bound; EXECUTE stmt_conv_drop_u; DEALLOCATE PREPARE stmt_conv_drop_u;

-- 会话唯一键改为按用户隔离
SET @conv_has_new_uk := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_conversation'
      AND INDEX_NAME = 'uk_im_conversation' AND COLUMN_NAME = 'user_id');
SET @conv_fix_uk := IF(@conv_has_new_uk = 0,
    'ALTER TABLE im_conversation DROP INDEX uk_im_conversation, ADD CONSTRAINT uk_im_conversation UNIQUE (channel_type, user_id, platform_conversation_id)',
    'SELECT 1');
PREPARE stmt_conv_uk FROM @conv_fix_uk; EXECUTE stmt_conv_uk; DEALLOCATE PREPARE stmt_conv_uk;

SET @conv_has_idx := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_conversation'
      AND INDEX_NAME = 'idx_im_conversation_user');
SET @conv_add_idx := IF(@conv_has_idx = 0,
    'ALTER TABLE im_conversation ADD INDEX idx_im_conversation_user (user_id)', 'SELECT 1');
PREPARE stmt_conv_idx FROM @conv_add_idx; EXECUTE stmt_conv_idx; DEALLOCATE PREPARE stmt_conv_idx;

-- 4. im_message_log：关联 user_id，删除 bound_username
SET @log_has_user := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_message_log' AND COLUMN_NAME = 'user_id');
SET @log_add_user := IF(@log_has_user = 0,
    'ALTER TABLE im_message_log ADD COLUMN user_id BIGINT NULL AFTER id', 'SELECT 1');
PREPARE stmt_log_add FROM @log_add_user; EXECUTE stmt_log_add; DEALLOCATE PREPARE stmt_log_add;

UPDATE im_message_log SET user_id = 1 WHERE user_id IS NULL;

SET @log_has_bound := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_message_log' AND COLUMN_NAME = 'bound_username');
SET @log_drop_bound := IF(@log_has_bound > 0,
    'ALTER TABLE im_message_log DROP COLUMN bound_username', 'SELECT 1');
PREPARE stmt_log_drop FROM @log_drop_bound; EXECUTE stmt_log_drop; DEALLOCATE PREPARE stmt_log_drop;

-- 消息去重键改为按用户隔离
SET @log_has_new_uk := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_message_log'
      AND INDEX_NAME = 'uk_im_msg_platform' AND COLUMN_NAME = 'user_id');
SET @log_fix_uk := IF(@log_has_new_uk = 0,
    'ALTER TABLE im_message_log DROP INDEX uk_im_msg_platform, ADD CONSTRAINT uk_im_msg_platform UNIQUE (channel_type, user_id, direction, platform_msg_id)',
    'SELECT 1');
PREPARE stmt_log_uk FROM @log_fix_uk; EXECUTE stmt_log_uk; DEALLOCATE PREPARE stmt_log_uk;

SET @log_has_idx := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_message_log'
      AND INDEX_NAME = 'idx_im_msg_user');
SET @log_add_idx := IF(@log_has_idx = 0,
    'ALTER TABLE im_message_log ADD INDEX idx_im_msg_user (user_id)', 'SELECT 1');
PREPARE stmt_log_idx FROM @log_add_idx; EXECUTE stmt_log_idx; DEALLOCATE PREPARE stmt_log_idx;
