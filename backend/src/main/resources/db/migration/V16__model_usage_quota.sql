CREATE TABLE model_pricing_rules (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    model_name VARCHAR(160) NOT NULL,
    model_type VARCHAR(16) NOT NULL,
    enabled BIT NOT NULL DEFAULT 1,
    input_price DECIMAL(18,8) NOT NULL DEFAULT 0,
    cache_hit_price DECIMAL(18,8) NOT NULL DEFAULT 0,
    output_price DECIMAL(18,8) NOT NULL DEFAULT 0,
    off_peak_input_price DECIMAL(18,8) NULL,
    off_peak_cache_hit_price DECIMAL(18,8) NULL,
    off_peak_output_price DECIMAL(18,8) NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_model_pricing_name (model_name)
);

-- 官网人民币目录价，核对日期 2026-09-08：
-- DeepSeek https://api-docs.deepseek.com/zh-cn/quick_start/pricing/
-- 智谱 https://docs.bigmodel.cn/cn/guide/start/pricing
-- 阿里云百炼 https://help.aliyun.com/zh/model-studio/model-pricing
INSERT INTO model_pricing_rules
    (model_name, model_type, input_price, cache_hit_price, output_price,
     off_peak_input_price, off_peak_cache_hit_price, off_peak_output_price)
VALUES
    ('deepseek-v4-flash', 'LLM', 3.00000000, 0.10000000, 9.00000000, 1.50000000, 0.05000000, 4.50000000),
    ('glm-5.3-flash', 'LLM', 0.80000000, 0.23000000, 2.80000000, NULL, NULL, NULL),
    ('text-embedding-v4', 'EMBEDDING', 0.50000000, 0.00000000, 0.00000000, NULL, NULL, NULL),
    ('qwen3-vl-rerank', 'RERANK', 0.50000000, 0.00000000, 0.00000000, NULL, NULL, NULL);

CREATE TABLE user_model_quotas (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    monthly_limit DECIMAL(18,8) NOT NULL DEFAULT 1.00000000,
    remaining_amount DECIMAL(18,8) NOT NULL DEFAULT 1.00000000,
    monthly_reset BIT NOT NULL DEFAULT 1,
    period_month DATE NOT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_user_model_quota_user (user_id),
    CONSTRAINT fk_user_model_quota_user FOREIGN KEY (user_id) REFERENCES users(id)
);

INSERT INTO user_model_quotas (user_id, period_month)
SELECT id, DATE_FORMAT(CURRENT_DATE, '%Y-%m-01') FROM users;

CREATE TABLE model_usage_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    username VARCHAR(255) NOT NULL,
    model_name VARCHAR(160) NOT NULL,
    model_type VARCHAR(16) NOT NULL,
    scenario VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    cache_hit_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    input_price DECIMAL(18,8) NOT NULL DEFAULT 0,
    cache_hit_price DECIMAL(18,8) NOT NULL DEFAULT 0,
    output_price DECIMAL(18,8) NOT NULL DEFAULT 0,
    reserved_amount DECIMAL(18,8) NOT NULL DEFAULT 0,
    amount DECIMAL(18,8) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    settled_at DATETIME NULL,
    INDEX idx_model_usage_user_time (user_id, created_at),
    INDEX idx_model_usage_model_time (model_name, created_at),
    CONSTRAINT fk_model_usage_user FOREIGN KEY (user_id) REFERENCES users(id)
);

ALTER TABLE audit_events MODIFY COLUMN reason VARCHAR(1000) NULL;
