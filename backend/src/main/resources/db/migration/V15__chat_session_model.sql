ALTER TABLE chat_sessions
    ADD COLUMN llm_config_id BIGINT NULL,
    ADD COLUMN llm_model_name VARCHAR(160) NULL;
