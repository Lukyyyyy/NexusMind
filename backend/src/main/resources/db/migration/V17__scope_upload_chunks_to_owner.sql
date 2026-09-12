ALTER TABLE chunk_info
    ADD COLUMN user_id VARCHAR(64) NOT NULL DEFAULT '__legacy__';

-- 唯一所有者的存量断点续传可以继续；归属不明确的旧分片保持隔离，用户重新上传即可。
UPDATE chunk_info c
JOIN (SELECT file_md5, MIN(user_id) AS owner_id FROM file_upload
      GROUP BY file_md5 HAVING COUNT(*) = 1) f ON c.file_md5 = f.file_md5
SET c.user_id = f.owner_id;

ALTER TABLE chunk_info
    DROP INDEX uk_chunk_info_file_md5_chunk_index,
    ADD UNIQUE INDEX uk_chunk_info_file_owner_index (file_md5, user_id, chunk_index);
