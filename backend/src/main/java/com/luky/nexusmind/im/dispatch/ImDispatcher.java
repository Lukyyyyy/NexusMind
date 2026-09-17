package com.luky.nexusmind.im.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luky.nexusmind.im.channel.ImChannelConfigService;
import com.luky.nexusmind.im.channel.ImChannelRuntime;
import com.luky.nexusmind.im.channel.clawbot.ClawbotWeChatAdapter;
import com.luky.nexusmind.im.model.ImChannelType;
import com.luky.nexusmind.im.model.ImInboundMessage;
import com.luky.nexusmind.im.model.ImMessageType;
import com.luky.nexusmind.im.model.ImTarget;
import com.luky.nexusmind.im.service.ImConversationService;
import com.luky.nexusmind.im.service.ImReplyService;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.UserRepository;
import com.luky.nexusmind.service.ChatHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 入站消息核心分发（多用户独立机器人架构）。
 *
 * 流程：
 * 1. 从消息 rawPayload 解析机器人 botId
 * 2. 通过 botId 找到机器人拥有者 userId
 * 3. 验证用户是否在该渠道绑定过微信账号
 * 4. 会话映射 -> 复用 ChatHandler 知识问答 -> 回复投递
 */
@Service
public class ImDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(ImDispatcher.class);

    private final ImChannelConfigService configService;
    private final ImConversationService conversationService;
    private final ImReplyService replyService;
    private final ChatHandler chatHandler;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final String appPublicUrl;

    public ImDispatcher(ImChannelConfigService configService,
                        ImConversationService conversationService,
                        ImReplyService replyService,
                        ChatHandler chatHandler,
                        UserRepository userRepository,
                        ObjectMapper objectMapper,
                        @Value("${app.public-url:}") String appPublicUrl) {
        this.configService = configService;
        this.conversationService = conversationService;
        this.replyService = replyService;
        this.chatHandler = chatHandler;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.appPublicUrl = appPublicUrl;
    }

    public void dispatch(ImInboundMessage message) {
        ImChannelType channel = message.channelType();
        if (channel == null) {
            logger.warn("未知 IM 渠道，忽略消息: {}", message.platformMsgId());
            return;
        }

        ImChannelRuntime runtime = configService.findRuntimeByBotId(channel, message);
        if (runtime == null) {
            logger.warn("无法确定机器人拥有者，忽略消息: {}", message.platformMsgId());
            return;
        }
        Long userId = runtime.userId();

        ImTarget target = message.target(extractContextToken(channel, message));
        String content = message.content() == null ? "" : message.content().strip();
        if (message.isGroup() && runtime.groupRequireMention()) {
            String stripped = stripMention(content);
            if (stripped == null) return;
            content = stripped;
        }

        User user = userRepository.findById(userId).orElse(null);
        if (user == null || !user.isEnabled()) {
            logger.warn("用户 {} 不存在或已被禁用", userId);
            return;
        }

        if (content.isEmpty()) {
            replyService.sendNotice(runtime, target, "暂不支持该消息类型，请直接输入文字提问。",
                    user.getUsername());
            return;
        }
        if (!ImMessageType.TEXT.name().equals(message.messageType())) {
            replyService.sendNotice(runtime, target, "当前仅支持文字提问。", user.getUsername());
            return;
        }

        Long chatSessionId = conversationService.resolveChatSessionId(
                userId, channel, message.conversationId(), user.getUsername());
        AutoCloseable typingSession;
        if (channel == ImChannelType.CLAWBOT_WECHAT && clawbotAdapter != null) {
            typingSession = clawbotAdapter.startTyping(runtime, target);
        } else {
            replyService.sendPlaceholder(runtime, target);
            typingSession = () -> { };
        }

        String replyKey = "im:" + message.dedupeKey();
        ImBufferedReplySink sink = new ImBufferedReplySink(replyKey, objectMapper,
                reply -> deliverAnswer(runtime, target, user.getUsername(), reply, typingSession));
        chatHandler.processMessage(user.getUsername(), chatSessionId, content, sink,
                String.valueOf(userId));
    }

    private void deliverAnswer(ImChannelRuntime runtime, ImTarget target, String username,
                               ImBufferedReplySink.Reply reply, AutoCloseable typingSession) {
        try {
            switch (reply.outcome()) {
                case FINISHED -> replyService.sendAnswer(runtime, target, reply.text(), username);
                case CANCELLED -> replyService.sendNotice(runtime, target, "回答已停止。", username);
                case ERROR -> replyService.sendNotice(runtime, target,
                        "回答生成失败：" + (reply.text() == null ? "请稍后重试" : reply.text()), username);
            }
        } catch (Exception e) {
            logger.error("IM 回复投递失败，渠道: {}, 会话: {}: {}",
                    runtime.channelType(), target.conversationId(), e.getMessage(), e);
        } finally {
            try {
                typingSession.close();
            } catch (Exception e) {
                logger.debug("关闭 IM 输入状态失败: {}", e.getMessage());
            }
        }
    }

    /** iLink 渠道回复必须原样带回入站 context_token；其他渠道返回空串。 */
    private String extractContextToken(ImChannelType channel, ImInboundMessage message) {
        if (channel != ImChannelType.CLAWBOT_WECHAT) return "";
        return clawbotAdapter == null ? "" : clawbotAdapter.takeContextToken(message);
    }

    @Autowired(required = false)
    private ClawbotWeChatAdapter clawbotAdapter;

    /** 返回 null 表示该群消息未 @ 机器人，应静默。 */
    private String stripMention(String content) {
        if (!content.startsWith("@")) return null;
        int space = content.indexOf(' ');
        int newline = content.indexOf('\n');
        int end = space < 0 ? newline : (newline < 0 ? space : Math.min(space, newline));
        if (end < 0) return "";
        return content.substring(end + 1).strip();
    }
}
