-- 新上传不再计算分片 MD5；保留可空列，待历史 SHA-256 回填完成后统一删除。
ALTER TABLE chunk_info MODIFY chunk_md5 VARCHAR(32) NULL;
