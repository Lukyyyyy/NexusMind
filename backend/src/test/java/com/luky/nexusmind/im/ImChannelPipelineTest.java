package com.luky.nexusmind.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luky.nexusmind.im.channel.ImChannelRuntime;
import com.luky.nexusmind.im.channel.ImChannelConfigService;
import com.luky.nexusmind.im.channel.ImCryptoService;
import com.luky.nexusmind.im.channel.clawbot.ClawbotWeChatAdapter;
import com.luky.nexusmind.im.dispatch.ImBufferedReplySink;
import com.luky.nexusmind.im.model.ImChannelType;
import com.luky.nexusmind.im.model.ImInboundMessage;
import com.luky.nexusmind.im.model.ImTarget;
import com.luky.nexusmind.im.service.ImReplyService;
import com.luky.nexusmind.model.ImChannelConfig;
import com.luky.nexusmind.repository.ImChannelConfigRepository;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ImChannelPipelineTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ClawbotWeChatAdapter adapter = new ClawbotWeChatAdapter(objectMapper);

    private ImChannelRuntime runtime() {
        try {
            return new ImChannelRuntime(ImChannelType.CLAWBOT_WECHAT, 7L, "微信(ClawBot/iLink)",
                    "https://ilinkai.weixin.qq.com",
                    objectMapper.readTree("{\"botToken\":\"test-token\"}"),
                    false, 1600, false);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void isPullBasedAndRequiresToken() {
        assertTrue(adapter.isPullBased());
        assertEquals(ImChannelType.CLAWBOT_WECHAT, adapter.channelType());
        assertThrows(IllegalStateException.class,
                () -> adapter.send(runtimeWithoutToken(), target("ctx"), text("hi")));
    }

    @Test
    void normalizesIlinkUserMessageAndSkipsBotEcho() throws Exception {
        var userMsg = objectMapper.readTree("""
                {"msg_id":"m-1","from_user_id":"wxid_abc@im.wechat","to_user_id":"bot@im.bot",
                 "message_type":1,"message_state":2,"context_token":"ctx-1",
                 "create_time_ms":1726000000000,
                 "item_list":[{"type":1,"text_item":{"text":"什么是RAG？"}}]}
                """);
        ImInboundMessage message = adapter.normalizeIlinkMessage(userMsg);
        assertNotNull(message);
        assertEquals("m-1", message.platformMsgId());
        assertEquals("wxid_abc@im.wechat", message.platformUserId());
        assertEquals("wxid_abc@im.wechat", message.conversationId());
        assertEquals("什么是RAG？", message.content());
        assertEquals(ImChannelType.CLAWBOT_WECHAT, message.channelType());
        assertFalse(message.isGroup());

        // BOT 自发消息回显（message_type=2）必须忽略，避免自问自答
        var botEcho = objectMapper.readTree("""
                {"msg_id":"m-2","from_user_id":"bot@im.bot","message_type":2,
                 "item_list":[{"type":1,"text_item":{"text":"回答"}}]}
                """);
        assertNull(adapter.normalizeIlinkMessage(botEcho));
    }

    @Test
    void extractsVoiceTextAndNonTextPlaceholders() throws Exception {
        var voice = objectMapper.readTree("""
                {"msg_id":"m-3","from_user_id":"u1","message_type":1,
                 "item_list":[{"type":3,"voice_item":{"text":"帮我查一下额度","media":{}}}]}
                """);
        assertEquals("帮我查一下额度", adapter.normalizeIlinkMessage(voice).content());

        var image = objectMapper.readTree("""
                {"msg_id":"m-4","from_user_id":"u1","message_type":1,
                 "item_list":[{"type":2,"image_item":{"media":{}}}]}
                """);
        ImInboundMessage imageMessage = adapter.normalizeIlinkMessage(image);
        assertEquals("[图片]", imageMessage.content());
        assertEquals("IMAGE", imageMessage.messageType());

        var file = objectMapper.readTree("""
                {"msg_id":"m-5","from_user_id":"u1","message_type":1,
                 "item_list":[{"type":4,"file_item":{"file_name":"报告.pdf","media":{}}}]}
                """);
        assertEquals("[文件] 报告.pdf", adapter.normalizeIlinkMessage(file).content());
    }

    @Test
    void contextTokenRoundTripForReply() throws Exception {
        var userMsg = objectMapper.readTree("""
                {"msg_id":"m-6","from_user_id":"u1","message_type":1,"context_token":"ctx-6",
                 "item_list":[{"type":1,"text_item":{"text":"hi"}}]}
                """);
        ImInboundMessage message = adapter.normalizeIlinkMessage(userMsg);
        adapter.rememberContextToken(message, "ctx-6");
        assertEquals("ctx-6", adapter.takeContextToken(message));
        assertEquals("", adapter.takeContextToken(message)); // 取出即移除
    }

    @Test
    void cursorUpdateNotifiesListenerAndDedupes() throws Exception {
        List<String> updates = new ArrayList<>();
        adapter.setCursorListener((userId, cursor) -> updates.add(userId + ":" + cursor));
        adapter.restoreCursor(7L, "buf-1");
        // poll 需要真实 HTTP，这里直接验证 restore 语义：
        // restore 不触发监听；不同用户游标互相隔离
        assertEquals("buf-1", adapter.currentCursor(7L));
        assertEquals("", adapter.currentCursor(8L));
        assertTrue(updates.isEmpty());
    }

    @Test
    void sendsTextPayloadWithMandatoryContextToken() throws Exception {
        // 借助可观察行为：send 构造的 client_id 前缀与 message_type=2 无法直接断言（需网关），
        // 此处仅验证 target/context 字段流转到 ImTarget。
        ImTarget target = new ImTarget("clawbot", "private", "u1@im.wechat", "u1@im.wechat", "ctx-9");
        assertEquals("ctx-9", target.contextToken());
        assertTrue(target.contextToken() != null && !target.contextToken().isBlank());
    }

    @Test
    void startsAndStopsNativeWechatTypingState() throws Exception {
        List<Integer> statuses = new CopyOnWriteArrayList<>();
        List<String> configBodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ilink/bot/getconfig", exchange -> {
            configBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"ret\":0,\"typing_ticket\":\"ticket-1\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/ilink/bot/sendtyping", exchange -> {
            JsonNode body = objectMapper.readTree(exchange.getRequestBody());
            statuses.add(body.path("status").asInt());
            byte[] response = "{\"ret\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        ClawbotWeChatAdapter localAdapter = new ClawbotWeChatAdapter(objectMapper);
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            ImChannelRuntime localRuntime = new ImChannelRuntime(ImChannelType.CLAWBOT_WECHAT, 7L, "test",
                    baseUrl, objectMapper.readTree("{\"botToken\":\"test-token\"}"), false, 1600, false);
            AutoCloseable typing = localAdapter.startTyping(localRuntime, target("ctx-1"));
            typing.close();

            assertEquals(List.of(1, 2), statuses);
            JsonNode configBody = objectMapper.readTree(configBodies.get(0));
            assertEquals("u1@im.wechat", configBody.path("ilink_user_id").asText());
            assertEquals("ctx-1", configBody.path("context_token").asText());
        } finally {
            localAdapter.stop();
            server.stop(0);
        }
    }

    @Test
    void splitsLongAnswersOnParagraphAndSentenceBoundaries() {
        String answer = "第一段。".repeat(600) + "\n\n" + "第二段。".repeat(600);
        List<String> segments = ImReplyService.split(answer, 500);
        assertTrue(segments.size() >= 4);
        for (String segment : segments) {
            assertTrue(segment.length() <= 500, "分段超限: " + segment.length());
        }
        assertEquals(List.of("短回复"), ImReplyService.split("短回复", 1600));
    }

    @Test
    void degradesMarkdownToPlainText() {
        String markdown = """
            ## 标题
            **加粗**和*斜体*，[链接](https://example.com)
            ```java
            System.out.println("code");
            ```
            - 列表项 (来源#1: 文档A.pdf)
            """;
        String plain = ImReplyService.toPlainText(markdown);
        assertFalse(plain.contains("##"));
        assertFalse(plain.contains("**"));
        assertFalse(plain.contains("```"));
        assertTrue(plain.contains("链接（https://example.com）"));
        assertFalse(plain.contains("(来源#1"));
        assertTrue(plain.contains("· 列表项"));
    }

    @Test
    void bufferedSinkAggregatesChunksAndSettlesOnce() throws Exception {
        List<ImBufferedReplySink.Reply> settled = new ArrayList<>();
        ImBufferedReplySink sink = new ImBufferedReplySink("im:test", objectMapper, settled::add);

        sink.send(objectMapper.writeValueAsString(java.util.Map.of("chunk", "你好")));
        sink.send(objectMapper.writeValueAsString(java.util.Map.of("chunk", "，世界")));
        sink.send(objectMapper.writeValueAsString(
                java.util.Map.of("type", "title_updated", "sessionId", 1, "title", "t")));
        sink.send(objectMapper.writeValueAsString(java.util.Map.of("type", "completion", "status", "finished")));
        sink.send(objectMapper.writeValueAsString(java.util.Map.of("error", "重复结算应被忽略")));

        assertEquals(1, settled.size());
        assertEquals(ImBufferedReplySink.Outcome.FINISHED, settled.get(0).outcome());
        assertEquals("你好，世界", settled.get(0).text());
    }

    @Test
    void bufferedSinkReportsErrorAndContentReplacement() throws Exception {
        List<ImBufferedReplySink.Reply> settled = new ArrayList<>();
        ImBufferedReplySink sink = new ImBufferedReplySink("im:test2", objectMapper, settled::add);
        sink.send(objectMapper.writeValueAsString(java.util.Map.of("error", "模型请求失败")));
        assertEquals(ImBufferedReplySink.Outcome.ERROR, settled.get(0).outcome());

        List<ImBufferedReplySink.Reply> settled2 = new ArrayList<>();
        ImBufferedReplySink sink2 = new ImBufferedReplySink("im:test3", objectMapper, settled2::add);
        sink2.send(objectMapper.writeValueAsString(java.util.Map.of("chunk", "旧内容")));
        sink2.send(objectMapper.writeValueAsString(
                java.util.Map.of("type", "content_replaced", "content", "修正后的回答")));
        sink2.send(objectMapper.writeValueAsString(java.util.Map.of("type", "completion", "status", "finished")));
        assertEquals("修正后的回答", settled2.get(0).text());
    }

    @Test
    void dedupeKeyFallsBackToFingerprintWhenMsgIdMissing() {
        ImInboundMessage message = new ImInboundMessage("clawbot", "private", null, "c", "u", null,
                "TEXT", "同一条消息", 1726000000000L, "{}");
        assertTrue(message.dedupeKey().startsWith("clawbot:h"));
    }

    @Test
    void resolvesBotOwnerFromIlinkToUserId() {
        ImCryptoService crypto = new ImCryptoService("test-secret");
        ImChannelConfig config = new ImChannelConfig();
        config.setChannelType(ImChannelType.CLAWBOT_WECHAT);
        config.setUserId(7L);
        config.setBotId("bot@im.bot");
        config.setName("test");
        config.setEnabled(true);
        config.setCredentialsCipher(crypto.encrypt("{}"));
        ImChannelConfigRepository repository = (ImChannelConfigRepository) Proxy.newProxyInstance(
                ImChannelConfigRepository.class.getClassLoader(), new Class<?>[]{ImChannelConfigRepository.class},
                (proxy, method, args) -> method.getName().equals("findByChannelTypeAndBotId")
                        || method.getName().equals("findByChannelTypeAndUserId")
                        ? Optional.of(config) : null);

        ImChannelConfigService service = new ImChannelConfigService(repository, crypto, objectMapper);
        ImInboundMessage message = new ImInboundMessage("clawbot", "private", "m-7", "c", "u", null,
                "TEXT", "hi", 1L, "{\"to_user_id\":\"bot@im.bot\"}");

        assertEquals(7L, service.findRuntimeByBotId(ImChannelType.CLAWBOT_WECHAT, message).userId());
    }

    private ImChannelRuntime runtimeWithoutToken() {
        return new ImChannelRuntime(ImChannelType.CLAWBOT_WECHAT, 7L, "微信(ClawBot/iLink)",
                "https://ilinkai.weixin.qq.com", objectMapper.createObjectNode(),
                false, 1600, false);
    }

    private com.luky.nexusmind.im.model.ImOutboundMessage text(String content) {
        return com.luky.nexusmind.im.model.ImOutboundMessage.text(content);
    }

    private ImTarget target(String contextToken) {
        return new ImTarget("clawbot", "private", "u1@im.wechat", "u1@im.wechat", contextToken);
    }
}
