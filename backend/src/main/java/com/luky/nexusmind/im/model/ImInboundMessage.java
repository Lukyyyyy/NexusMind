package com.luky.nexusmind.im.model;

/**
 * 渠道无关的入站消息。字段全部为 String/基本类型，可直接作为 Kafka JSON 载荷，
 * 无需 JavaTimeModule 或多态类型支持。
 */
public record ImInboundMessage(
        String channel,             // ImChannelType.code()
        String chatType,            // private | group
        String platformMsgId,       // 平台消息 id，幂等去重键
        String conversationId,      // 平台会话 id（单聊时为对方 id）
        String platformUserId,      // 发送者在平台内的稳定 id
        String platformUserName,    // 展示名，仅用于绑定引导文案
        String messageType,         // ImMessageType.name()
        String content,
        long timestampEpochMs,
        String rawPayload
) {
    public ImChannelType channelType() {
        return ImChannelType.fromCode(channel);
    }

    public boolean isGroup() {
        return "group".equalsIgnoreCase(chatType);
    }

    public ImTarget target() {
        return target("");
    }

    /** contextToken 由 iLink 类渠道在构造时传入。 */
    public ImTarget target(String contextToken) {
        return new ImTarget(channel, chatType, conversationId, platformUserId, contextToken);
    }

    /** 去重键：渠道 + 平台消息 id（消息 id 缺失时退化为内容指纹+时间）。 */
    public String dedupeKey() {
        String msgPart = (platformMsgId == null || platformMsgId.isBlank())
                ? "h" + Integer.toHexString((content == null ? "" : content).hashCode()) + ":" + timestampEpochMs / 60_000L
                : platformMsgId;
        return channel + ":" + msgPart;
    }
}
