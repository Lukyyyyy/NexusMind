package com.luky.nexusmind.im.service;

import com.luky.nexusmind.im.channel.ImChannelAdapter;
import com.luky.nexusmind.im.channel.ImChannelRegistry;
import com.luky.nexusmind.im.channel.ImChannelRuntime;
import com.luky.nexusmind.im.model.ImOutboundMessage;
import com.luky.nexusmind.im.model.ImTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 出站回复：Markdown 降级、长文分段、限流间隔与发送日志。
 * 所有异常在此吸收，避免打断聊天链路。
 */
@Service
public class ImReplyService {

    private static final Logger logger = LoggerFactory.getLogger(ImReplyService.class);
    private static final long SEGMENT_INTERVAL_MS = 400;
    private static final Pattern CODE_FENCE = Pattern.compile("(?s)```[a-zA-Z0-9]*\\n?|```");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s*", Pattern.MULTILINE);
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__");
    private static final Pattern ITALIC = Pattern.compile("(?<![\\w*])\\*([^*\\n]+)\\*(?![\\w*])");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\(([^)\\s]+)[^)]*\\)");
    private static final Pattern CITATION = Pattern.compile("\\(来源#\\d+:[^)]*\\)");

    private final ImChannelRegistry registry;
    private final ImMessageLogService messageLogService;

    public ImReplyService(ImChannelRegistry registry, ImMessageLogService messageLogService) {
        this.registry = registry;
        this.messageLogService = messageLogService;
    }

    /** 发送最终答案：渠道不支持富文本时降级为纯文本，超长自动分段。 */
    public void sendAnswer(ImChannelRuntime channel, ImTarget target, String answer, String boundUsername) {
        String normalized = channel.channelType().supportsRichText() ? answer : toPlainText(answer);
        sendSegments(channel, target, normalized, boundUsername);
    }

    /** 发送简短系统提示（绑定引导、错误兜底等），不做 Markdown 处理。 */
    public void sendNotice(ImChannelRuntime channel, ImTarget target, String notice, String boundUsername) {
        sendSegments(channel, target, notice, boundUsername);
    }

    public void sendPlaceholder(ImChannelRuntime channel, ImTarget target) {
        if (!channel.placeholderEnabled()) return;
        try {
            adapter(channel).send(channel, target, ImOutboundMessage.text("正在思考中，请稍候…"));
        } catch (Exception e) {
            logger.warn("IM 占位消息发送失败（忽略）: {}", e.getMessage());
        }
    }

    private void sendSegments(ImChannelRuntime channel, ImTarget target, String content, String boundUsername) {
        ImChannelAdapter adapter = adapter(channel);
        List<String> segments = split(content == null ? "" : content, Math.max(200, channel.maxOutboundChars()));
        for (int i = 0; i < segments.size(); i++) {
            boolean success = false;
            String error = null;
            try {
                adapter.send(channel, target, ImOutboundMessage.text(segments.get(i)));
                success = true;
            } catch (Exception e) {
                error = e.getMessage();
                logger.error("IM 回复发送失败，渠道: {}, 会话: {}, 段 {}/{}: {}",
                        channel.channelType(), target.conversationId(), i + 1, segments.size(), e.getMessage());
            }
            messageLogService.recordOutbound(channel.channelType(), target, segments.get(i), success,
                    boundUsername, error);
            if (i < segments.size() - 1) {
                sleep(SEGMENT_INTERVAL_MS);
            }
        }
    }

    private ImChannelAdapter adapter(ImChannelRuntime channel) {
        return registry.require(channel.channelType());
    }

    /** 按空行段落聚合，超限段落再按换行/句号硬切。 */
    public static List<String> split(String content, int maxChars) {
        List<String> result = new ArrayList<>();
        String[] paragraphs = content.split("\\n{2,}");
        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs) {
            String block = paragraph.stripTrailing();
            while (block.length() > maxChars) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
                int cut = hardCutIndex(block, maxChars);
                result.add(block.substring(0, cut));
                block = block.substring(cut);
            }
            if (current.length() + block.length() + 2 > maxChars && !current.isEmpty()) {
                result.add(current.toString());
                current.setLength(0);
            }
            if (block.isEmpty()) continue;
            if (!current.isEmpty()) current.append("\n\n");
            current.append(block);
        }
        if (!current.isEmpty()) result.add(current.toString());
        if (result.isEmpty()) result.add(content.isEmpty() ? "（空回复）" : content);
        return result;
    }

    private static int hardCutIndex(String block, int maxChars) {
        int searchEnd = Math.min(block.length(), maxChars);
        int newline = block.lastIndexOf('\n', searchEnd - 1);
        if (newline > maxChars / 2) return newline + 1;
        for (String stop : new String[]{"。", "！", "？", "；", ". ", "! ", "? "}) {
            int idx = block.lastIndexOf(stop, searchEnd - 1);
            if (idx > maxChars / 2) return idx + stop.length();
        }
        return maxChars;
    }

    /** Markdown → 微信可读纯文本。 */
    public static String toPlainText(String markdown) {
        if (markdown == null) return "";
        String text = CODE_FENCE.matcher(markdown).replaceAll("");
        text = HEADING.matcher(text).replaceAll("");
        text = BOLD.matcher(text).replaceAll("$1$2");
        text = ITALIC.matcher(text).replaceAll("$1");
        text = LINK.matcher(text).replaceAll("$1（$2）");
        text = text.replaceAll("(?m)^\\s*[-*+]\\s+", "· ");
        text = CITATION.matcher(text).replaceAll("");
        return text.replaceAll("\\n{3,}", "\n\n").strip();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
