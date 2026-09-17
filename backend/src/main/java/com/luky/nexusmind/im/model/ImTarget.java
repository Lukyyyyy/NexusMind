package com.luky.nexusmind.im.model;

/**
 * 回复目标：渠道 + 会话类型 + 平台会话/用户 id。
 * contextToken 为 iLink 等"回复必须携带入站上下文令牌"的渠道使用，推送型渠道可忽略。
 */
public record ImTarget(
        String channel,
        String chatType,
        String conversationId,
        String platformUserId,
        String contextToken
) {
    public ImChannelType channelType() {
        return ImChannelType.fromCode(channel);
    }

    public boolean isGroup() {
        return "group".equalsIgnoreCase(chatType);
    }
}
