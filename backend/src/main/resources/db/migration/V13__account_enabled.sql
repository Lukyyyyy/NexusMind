ALTER TABLE users
    ADD COLUMN enabled BIT NOT NULL DEFAULT 1,
    ADD COLUMN session_version BIGINT NOT NULL DEFAULT 0;

UPDATE users SET enabled = 1;
