package com.luky.nexusmind.im.model;

/** 出站消息（Phase 1 仅文本；富文本由各 Adapter 内部降级为纯文本）。 */
public record ImOutboundMessage(String messageType, String content) {
    public static ImOutboundMessage text(String content) {
        return new ImOutboundMessage(ImMessageType.TEXT.name(), content);
    }
}
