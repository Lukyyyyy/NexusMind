package com.luky.nexusmind.im.channel.clawbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luky.nexusmind.im.channel.ImChannelAdapter;
import com.luky.nexusmind.im.channel.ImChannelRuntime;
import com.luky.nexusmind.im.model.ImChannelType;
import com.luky.nexusmind.im.model.ImInboundMessage;
import com.luky.nexusmind.im.model.ImMessageType;
import com.luky.nexusmind.im.model.ImOutboundMessage;
import com.luky.nexusmind.im.model.ImRawEvent;
import com.luky.nexusmind.im.model.ImTarget;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 微信 ClawBot（iLink 协议）适配器——直连微信官方 iLink Bot API，无需自建网关。
 *
 * <p>协议要点（ilinkai.weixin.qq.com）：</p>
 * <ul>
 *   <li>登录：GET /ilink/bot/get_bot_qrcode?bot_type=3 取二维码，轮询
 *       /ilink/bot/get_qrcode_status 至 confirmed 拿到 bot_token（见 ImIlinkLoginService）；</li>
 *   <li>收消息：POST /ilink/bot/getupdates 长轮询，get_updates_buf 为同步游标，
 *       必须原样保存并回传（持久化在 im_channel_config.credentials JSON 的 syncCursor 字段）；</li>
 *   <li>发消息：POST /ilink/bot/sendmessage，message_type=2（BOT），message_state=2（FINISH），
 *       context_token 必须携带入站消息原值，否则无法投递到用户会话；</li>
 *   <li>公共头：AuthorizationType: ilink_bot_token + Authorization: Bearer {token}
 *       + X-WECHAT-UIN（base64 随机 uint32）。</li>
 * </ul>
 *
 * <p>Phase 1 仅处理文本（item type=1）；语音转写文字（type=3 带 text）一并透传为文本。
 * 媒体收发（type=2/4/5，CDN + AES-128-ECB）留待 Phase 2。</p>
 */
@Component
public class ClawbotWeChatAdapter implements ImChannelAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ClawbotWeChatAdapter.class);
    static final String DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com";
    private static final String CHANNEL_VERSION = "1.0.2";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration LONG_POLL_TIMEOUT = Duration.ofSeconds(42);
    private static final Duration TYPING_TIMEOUT = Duration.ofSeconds(5);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ScheduledExecutorService typingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ilink-typing");
        thread.setDaemon(true);
        return thread;
    });

    /** 每个用户的同步游标独立缓存；启动时按用户恢复，每次长轮询后写回该用户的行。 */
    private final java.util.concurrent.ConcurrentHashMap<Long, String> syncCursors =
            new java.util.concurrent.ConcurrentHashMap<>();

    public ClawbotWeChatAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ImChannelType channelType() {
        return ImChannelType.CLAWBOT_WECHAT;
    }

    @Override
    public boolean isPullBased() {
        return true;
    }

    /** 恢复指定用户上次持久化的同步游标。 */
    public void restoreCursor(Long userId, String cursor) {
        if (userId != null && cursor != null) syncCursors.put(userId, cursor);
    }

    public String currentCursor(Long userId) {
        return syncCursors.getOrDefault(userId, "");
    }

    /** 游标变更监听：key 为 userId，用于把 get_updates_buf 写回对应用户的渠道配置行。 */
    private volatile java.util.function.BiConsumer<Long, String> cursorListener;

    public void setCursorListener(java.util.function.BiConsumer<Long, String> listener) {
        this.cursorListener = listener;
    }

    /** 兼容旧单监听签名（测试用），视为 userId=1 的写入。 */
    void setCursorListener(java.util.function.Consumer<String> listener) {
        this.cursorListener = (userId, cursor) -> listener.accept(cursor);
    }

    private void updateCursor(Long userId, String newCursor) {
        String previous = syncCursors.put(userId, newCursor);
        if (!newCursor.equals(previous) && cursorListener != null) {
            try {
                cursorListener.accept(userId, newCursor);
            } catch (Exception e) {
                logger.warn("同步游标持久化失败（下次重启可能重复消费）: {}", e.getMessage());
            }
        }
    }

    // ---------------- 拉取型：长轮询收消息 ----------------

    @Override
    public List<ImInboundMessage> poll(ImChannelRuntime channel) throws Exception {
        Long userId = channel.userId();
        String token = requireToken(channel);
        String baseUrl = resolveBaseUrl(channel);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("get_updates_buf", userId == null ? "" : currentCursor(userId));
        body.putObject("base_info").put("channel_version", CHANNEL_VERSION);

        JsonNode resp = postJson(baseUrl, "ilink/bot/getupdates", body, token, LONG_POLL_TIMEOUT);
        int ret = resp.path("ret").asInt(0);
        if (ret != 0) {
            throw new IllegalStateException("iLink getupdates 失败 ret=" + ret);
        }

        String newBuf = resp.path("get_updates_buf").asText("");
        if (!newBuf.isBlank() && userId != null) {
            updateCursor(userId, newBuf);
        }

        List<ImInboundMessage> messages = new ArrayList<>();
        JsonNode msgs = resp.path("msgs");
        if (msgs.isArray()) {
            for (JsonNode msg : msgs) {
                ImInboundMessage inbound = normalizeIlinkMessage(msg);
                if (inbound != null) messages.add(inbound);
            }
        }
        return messages;
    }

    /** iLink getupdates 单条 msg -> 统一消息模型。 */
    public ImInboundMessage normalizeIlinkMessage(JsonNode msg) {
        // message_type: 1=用户消息；BOT 自己发出的回显为 2，直接忽略避免自问自答循环
        int messageType = msg.path("message_type").asInt(0);
        if (messageType != 1) return null;

        String fromUserId = msg.path("from_user_id").asText("");
        if (fromUserId.isBlank()) return null;
        String contextToken = msg.path("context_token").asText("");
        long timestampMs = msg.path("create_time_ms").asLong(System.currentTimeMillis());
        String msgId = msg.path("msg_id").asText("");
        if (msgId.isBlank()) msgId = "seq-" + timestampMs + "-" + fromUserId.hashCode();

        String content = "";
        ImMessageType type = ImMessageType.TEXT;
        for (JsonNode item : msg.path("item_list")) {
            int itemType = item.path("type").asInt(0);
            if (itemType == 1 && item.path("text_item").hasNonNull("text")) {
                content = item.path("text_item").path("text").asText();
                type = ImMessageType.TEXT;
                break;
            }
            if (itemType == 3 && item.path("voice_item").hasNonNull("text")) {
                content = item.path("voice_item").path("text").asText();
                type = ImMessageType.TEXT;
                break;
            }
            if (itemType == 2) {
                content = "[图片]";
                type = ImMessageType.IMAGE;
                break;
            }
            if (itemType == 4) {
                content = "[文件] " + item.path("file_item").path("file_name").asText("");
                type = ImMessageType.FILE;
                break;
            }
            if (itemType == 5) {
                content = "[视频]";
                type = ImMessageType.UNKNOWN;
                break;
            }
        }

        String raw;
        try {
            raw = objectMapper.writeValueAsString(msg);
        } catch (Exception e) {
            raw = "";
        }

        return new ImInboundMessage(
                ImChannelType.CLAWBOT_WECHAT.code(),
                "private", // iLink Bot 当前为单聊场景
                msgId,
                fromUserId,
                fromUserId,
                displayNameFromId(fromUserId),
                type.name(),
                content,
                timestampMs,
                raw);
    }

    /** iLink 上下文令牌随消息传递：Adapter 侧无法直接挂到 record 上，由 Dispatcher 使用 target(contextToken)。 */
    private final java.util.concurrent.ConcurrentHashMap<String, String> contextTokens =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 取出并移除该消息对应的 context_token（发送时必须原样带回）。 */
    public String takeContextToken(ImInboundMessage message) {
        String token = contextTokens.remove(String.valueOf(message.dedupeKey()));
        return token == null ? "" : token;
    }

    public void rememberContextToken(ImInboundMessage message, String contextToken) {
        if (contextToken != null && !contextToken.isBlank()) {
            contextTokens.put(String.valueOf(message.dedupeKey()), contextToken);
        }
    }

    // ---------------- 推送型接口（iLink 直连下不使用，保留兼容实现） ----------------

    @Override
    public boolean verify(ImChannelRuntime channel, ImRawEvent rawEvent) {
        // 拉取型渠道没有平台回调，无签名可验；是否配置 bot_token 由 requireRuntime/requireToken 把关。
        return true;
    }

    @Override
    public ImInboundMessage normalize(ImRawEvent rawEvent) {
        return null; // 拉取型渠道不处理 POST 回调
    }

    // ---------------- 发送 ----------------

    @Override
    public String send(ImChannelRuntime channel, ImTarget target, ImOutboundMessage message) throws Exception {
        String token = requireToken(channel);
        String baseUrl = resolveBaseUrl(channel);

        // iLink 回复必须携带入站 context_token；记录在 ImTarget.contextToken 上。
        String contextToken = target.contextToken() == null ? "" : target.contextToken();
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("from_user_id", "");
        msg.put("to_user_id", target.conversationId());
        msg.put("client_id", "nm-" + UUID.randomUUID());
        msg.put("message_type", 2);
        msg.put("message_state", 2);
        msg.put("context_token", contextToken);
        ArrayNode items = msg.putArray("item_list");
        ObjectNode textItem = items.addObject();
        textItem.put("type", 1);
        textItem.putObject("text_item").put("text", message.content() == null ? "" : message.content());

        ObjectNode body = objectMapper.createObjectNode();
        body.set("msg", msg);
        body.putObject("base_info").put("channel_version", CHANNEL_VERSION);

        JsonNode resp = postJson(baseUrl, "ilink/bot/sendmessage", body, token, HTTP_TIMEOUT);
        int ret = resp.path("ret").asInt(0);
        if (ret != 0) {
            throw new IllegalStateException("iLink sendmessage 失败 ret=" + ret);
        }
        return msg.path("client_id").asText(null);
    }

    /** 开启微信原生“对方正在输入中”提示；关闭返回值时停止提示。 */
    public AutoCloseable startTyping(ImChannelRuntime channel, ImTarget target) {
        String contextToken = target.contextToken() == null ? "" : target.contextToken();
        if (contextToken.isBlank()) return () -> { };
        try {
            String token = requireToken(channel);
            String baseUrl = resolveBaseUrl(channel);
            ObjectNode configBody = objectMapper.createObjectNode();
            configBody.put("ilink_user_id", target.conversationId());
            configBody.put("context_token", contextToken);
            configBody.putObject("base_info").put("channel_version", CHANNEL_VERSION);
            JsonNode config = postJson(baseUrl, "ilink/bot/getconfig", configBody, token, TYPING_TIMEOUT);
            if (config.path("ret").asInt(0) != 0) {
                throw new IllegalStateException("iLink getconfig 失败 ret=" + config.path("ret").asInt());
            }
            String ticket = config.path("typing_ticket").asText("");
            if (ticket.isBlank()) ticket = config.path("data").path("typing_ticket").asText("");
            if (ticket.isBlank()) throw new IllegalStateException("iLink getconfig 未返回 typing_ticket");

            sendTyping(baseUrl, token, target.conversationId(), ticket, 1);
            AtomicBoolean closed = new AtomicBoolean(false);
            String typingTicket = ticket;
            ScheduledFuture<?> keepalive = typingScheduler.scheduleAtFixedRate(() -> {
                if (closed.get()) return;
                try {
                    sendTyping(baseUrl, token, target.conversationId(), typingTicket, 1);
                } catch (Exception e) {
                    logger.debug("iLink 输入状态续期失败: {}", e.getMessage());
                }
            }, 5, 5, TimeUnit.SECONDS);
            return () -> {
                if (!closed.compareAndSet(false, true)) return;
                keepalive.cancel(false);
                try {
                    sendTyping(baseUrl, token, target.conversationId(), typingTicket, 2);
                } catch (Exception e) {
                    logger.debug("iLink 输入状态关闭失败: {}", e.getMessage());
                }
            };
        } catch (Exception e) {
            logger.warn("iLink 输入状态开启失败（不影响回答）: {}", e.getMessage());
            return () -> { };
        }
    }

    private void sendTyping(String baseUrl, String token, String userId, String ticket, int status) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("ilink_user_id", userId);
        body.put("typing_ticket", ticket);
        body.put("status", status);
        body.putObject("base_info").put("channel_version", CHANNEL_VERSION);
        JsonNode response = postJson(baseUrl, "ilink/bot/sendtyping", body, token, TYPING_TIMEOUT);
        if (response.path("ret").asInt(0) != 0) {
            throw new IllegalStateException("iLink sendtyping 失败 ret=" + response.path("ret").asInt());
        }
    }

    @PreDestroy
    public void stop() {
        typingScheduler.shutdownNow();
    }

    // ---------------- HTTP 与工具 ----------------

    private JsonNode postJson(String baseUrl, String path, ObjectNode body, String token, Duration timeout)
            throws Exception {
        String payload = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("AuthorizationType", "ilink_bot_token")
                .header("Authorization", "Bearer " + token)
                .header("X-WECHAT-UIN", randomUin())
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("iLink " + path + " HTTP " + response.statusCode()
                    + ": " + response.body());
        }
        String responseBody = response.body();
        if (responseBody == null || responseBody.isBlank()) return objectMapper.createObjectNode();
        return objectMapper.readTree(responseBody);
    }

    static String randomUin() {
        long value = (long) (Math.random() * 0xFFFFFFFFL);
        return Base64.getEncoder().encodeToString(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
    }

    private String requireToken(ImChannelRuntime channel) {
        String token = channel.credentialText("botToken");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("iLink 渠道未配置 botToken，请先完成扫码登录");
        }
        return token;
    }

    private String resolveBaseUrl(ImChannelRuntime channel) {
        String baseUrl = channel.baseUrl();
        if (baseUrl != null && !baseUrl.isBlank()) {
            return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        }
        return DEFAULT_BASE_URL;
    }

    private String displayNameFromId(String userId) {
        int at = userId == null ? -1 : userId.indexOf('@');
        return at > 0 ? userId.substring(0, at) : userId;
    }
}
