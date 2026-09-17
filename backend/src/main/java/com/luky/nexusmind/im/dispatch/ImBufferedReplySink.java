package com.luky.nexusmind.im.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luky.nexusmind.service.ChatReplySink;

import java.util.function.Consumer;

/**
 * IM 场景的 ChatReplySink：不逐 token 推送，而是缓冲 ChatHandler 的 WebSocket 协议事件
 * （chunk / content_replaced / completion / error），在流结束时一次性交给回复服务。
 */
public final class ImBufferedReplySink implements ChatReplySink {

    public enum Outcome { FINISHED, CANCELLED, ERROR }

    public record Reply(Outcome outcome, String text) {
    }

    private final String id;
    private final ObjectMapper objectMapper;
    private final Consumer<Reply> onSettled;
    private final StringBuilder buffer = new StringBuilder();
    private volatile boolean settled;

    public ImBufferedReplySink(String id, ObjectMapper objectMapper, Consumer<Reply> onSettled) {
        this.id = id;
        this.objectMapper = objectMapper;
        this.onSettled = onSettled;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public void send(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (node.hasNonNull("error")) {
                settle(Outcome.ERROR, node.get("error").asText());
                return;
            }
            String type = node.path("type").asText("");
            switch (type) {
                case "content_replaced" -> {
                    synchronized (buffer) {
                        buffer.setLength(0);
                        buffer.append(node.path("content").asText(""));
                    }
                }
                case "completion" -> {
                    boolean cancelled = "cancelled".equals(node.path("status").asText(""));
                    settle(cancelled ? Outcome.CANCELLED : Outcome.FINISHED, snapshot());
                }
                case "stop" -> settle(Outcome.CANCELLED, snapshot());
                case "title_updated" -> {
                    // IM 无侧边栏，标题更新仅落库不回传
                }
                default -> {
                    if (node.hasNonNull("chunk")) {
                        synchronized (buffer) {
                            buffer.append(node.get("chunk").asText(""));
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 非 JSON 片段（理论上不会出现）按纯文本累积
            synchronized (buffer) {
                buffer.append(payload);
            }
        }
    }

    private String snapshot() {
        synchronized (buffer) {
            return buffer.toString();
        }
    }

    private void settle(Outcome outcome, String text) {
        synchronized (this) {
            if (settled) return;
            settled = true;
        }
        onSettled.accept(new Reply(outcome, text));
    }
}
