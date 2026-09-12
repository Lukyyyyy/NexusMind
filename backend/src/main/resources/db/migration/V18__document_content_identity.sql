ALTER TABLE file_upload ADD COLUMN content_md5 VARCHAR(32) NULL;
ALTER TABLE file_upload ADD COLUMN content_sha256 VARCHAR(64) NULL;
ALTER TABLE file_upload ADD COLUMN legacy_shared BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE file_upload SET content_md5 = LOWER(file_md5) WHERE content_md5 IS NULL;
UPDATE file_upload f JOIN file_upload other
  ON other.file_md5 = f.file_md5 AND other.id <> f.id
SET f.legacy_shared = TRUE;
CREATE UNIQUE INDEX uk_file_upload_owner_org_content ON file_upload (user_id, org_tag, content_sha256);
