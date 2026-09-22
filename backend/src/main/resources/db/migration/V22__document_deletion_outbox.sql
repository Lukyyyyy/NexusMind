CREATE TABLE document_deletion_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    file_id BIGINT NULL,
    file_md5 VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_document_deletion_outbox_file_owner (file_md5, user_id),
    KEY idx_document_deletion_outbox_due (next_attempt_at)
);
