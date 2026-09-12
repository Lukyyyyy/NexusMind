package com.luky.nexusmind.service;

import org.apache.commons.codec.digest.DigestUtils;

/** Document key is scoped to its owner and organization; content MD5 is only an upload checksum. */
public final class DocumentIdentity {
    private DocumentIdentity() {}

    public static String key(String userId, String orgTag, String contentSha256) {
        if (userId == null || userId.isBlank() || orgTag == null || orgTag.isBlank()
                || contentSha256 == null || !contentSha256.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("无效的文档归属或内容摘要");
        }
        return DigestUtils.sha256Hex(userId + "\0" + orgTag + "\0" + contentSha256.toLowerCase())
                .substring(0, 32);
    }
}
