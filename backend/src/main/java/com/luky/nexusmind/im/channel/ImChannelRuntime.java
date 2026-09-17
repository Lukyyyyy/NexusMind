package com.luky.nexusmind.im.channel;

import com.luky.nexusmind.im.model.ImChannelType;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 已完成凭据解密的渠道运行时配置，传给 Adapter 使用，避免各 Adapter 接触密文。
 *
 * 每用户独立机器人：userId 标识该渠道实例的拥有者，用于游标隔离、配额归属等。
 */
public record ImChannelRuntime(
        ImChannelType channelType,
        Long userId,
        String name,
        String baseUrl,
        JsonNode credentials,
        boolean groupRequireMention,
        int maxOutboundChars,
        boolean placeholderEnabled
) {
    public String credentialText(String field) {
        JsonNode node = credentials == null ? null : credentials.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }
}
