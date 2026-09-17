package com.luky.nexusmind.im.model;

/**
 * IM 渠道类型。能力开关随枚举声明，上层功能按渠道能力自动降级。
 */
public enum ImChannelType {
    /** 微信个人号，经官方 iLink Bot API（ClawBot 插件）直连，拉取型渠道。 */
    CLAWBOT_WECHAT("clawbot", "微信(ClawBot/iLink)", false, false),
    /** 企业微信自建应用。 */
    WECOM("wecom", "企业微信", true, true),
    /** 飞书自建应用。 */
    FEISHU("feishu", "飞书", true, true);

    private final String code;
    private final String displayName;
    private final boolean supportsProactivePush;
    private final boolean supportsRichText;

    ImChannelType(String code, String displayName, boolean supportsProactivePush, boolean supportsRichText) {
        this.code = code;
        this.displayName = displayName;
        this.supportsProactivePush = supportsProactivePush;
        this.supportsRichText = supportsRichText;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    public boolean supportsProactivePush() {
        return supportsProactivePush;
    }

    public boolean supportsRichText() {
        return supportsRichText;
    }

    public static ImChannelType fromCode(String code) {
        if (code == null) return null;
        String normalized = code.trim().toLowerCase();
        for (ImChannelType type : values()) {
            if (type.code.equals(normalized)) return type;
        }
        return null;
    }
}
