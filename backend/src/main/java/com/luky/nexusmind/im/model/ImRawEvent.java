package com.luky.nexusmind.im.model;

import java.util.Map;

/**
 * 渠道回调/桥接的原始事件，保留未解析的请求体用于签名校验。
 */
public record ImRawEvent(
        String channel,
        Map<String, String> headers,
        String body
) {
    public String header(String name) {
        if (headers == null) return null;
        String value = headers.get(name);
        if (value == null) value = headers.get(name.toLowerCase());
        return value;
    }
}
