package com.luky.nexusmind.im.channel;

import com.luky.nexusmind.im.model.ImChannelType;
import com.luky.nexusmind.im.model.ImInboundMessage;
import com.luky.nexusmind.im.model.ImOutboundMessage;
import com.luky.nexusmind.im.model.ImRawEvent;
import com.luky.nexusmind.im.model.ImTarget;

/**
 * IM 渠道适配器 SPI。新接入一个平台 = 新增一个 Adapter + 一条渠道配置，核心链路零改动。
 *
 * 渠道分为两种收消息模式：
 * <ul>
 *   <li>推送型（企微/飞书/网关桥接）：平台 POST 回调到 ImCallbackController，走
 *       {@link #verify} + {@link #normalize}；</li>
 *   <li>拉取型（iLink 长轮询）：{@link #isPullBased()} 返回 true，由
 *       ImPullPollingRunner 周期调用 {@link #poll} 拉取消息。</li>
 * </ul>
 */
public interface ImChannelAdapter {

    ImChannelType channelType();

    /** 是否为拉取型渠道（长轮询/主动拉取）。默认 false，即推送回调型。 */
    default boolean isPullBased() {
        return false;
    }

    /** 校验回调/事件合法性（签名、token、时间戳防重放）。 */
    boolean verify(ImChannelRuntime channel, ImRawEvent rawEvent);

    /** 把平台原始报文归一化为统一消息模型；无法识别或无需处理的事件返回 null。 */
    ImInboundMessage normalize(ImRawEvent rawEvent);

    /** 向目标会话发送一条消息（调用方已完成分段与格式降级），返回平台消息 id 或 null。 */
    String send(ImChannelRuntime channel, ImTarget target, ImOutboundMessage message) throws Exception;

    /**
     * 拉取型渠道：执行一次长轮询，返回归一化后的新消息（可空）。
     * 仅在 {@link #isPullBased()} 为 true 时被调用。
     */
    default java.util.List<ImInboundMessage> poll(ImChannelRuntime channel) throws Exception {
        return java.util.List.of();
    }

    /**
     * GET 回调的 URL 验证挑战（企微/飞书接入时实现）；默认不支持。
     * 返回 null 表示该渠道不做 GET 验证。
     */
    default String verifyChallenge(ImChannelRuntime channel, ImRawEvent rawEvent) {
        return null;
    }
}
