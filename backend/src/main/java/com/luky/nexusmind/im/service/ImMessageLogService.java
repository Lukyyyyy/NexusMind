package com.luky.nexusmind.im.service;

import com.luky.nexusmind.im.model.ImChannelType;
import com.luky.nexusmind.im.model.ImInboundMessage;
import com.luky.nexusmind.im.model.ImTarget;
import com.luky.nexusmind.model.ImMessageLog;
import com.luky.nexusmind.repository.ImMessageLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** 消息日志与幂等去重：入站以 (channel, IN, dedupeKey) 唯一索引作为事实来源。 */
@Service
public class ImMessageLogService {

    private static final Logger logger = LoggerFactory.getLogger(ImMessageLogService.class);
    private static final int CONTENT_PREVIEW_LIMIT = 4000;

    private final ImMessageLogRepository logRepository;

    public ImMessageLogService(ImMessageLogRepository logRepository) {
        this.logRepository = logRepository;
    }

    /** 返回 true 表示首次见到该消息；false 表示重复投递。 */
    public boolean recordInbound(ImInboundMessage message) {
        ImMessageLog entry = new ImMessageLog();
        entry.setChannelType(message.channelType());
        entry.setDirection(ImMessageLog.Direction.IN);
        entry.setStatus(ImMessageLog.Status.RECEIVED);
        entry.setPlatformMsgId(message.dedupeKey());
        entry.setPlatformConversationId(message.conversationId());
        entry.setPlatformUserId(message.platformUserId());
        entry.setContent(truncate(message.content()));
        return save(entry);
    }

    public void recordOutbound(ImChannelType channel, ImTarget target, String content, boolean success,
                              String boundUsername, String errorMsg) {
        ImMessageLog entry = new ImMessageLog();
        entry.setChannelType(channel);
        entry.setDirection(ImMessageLog.Direction.OUT);
        entry.setStatus(success ? ImMessageLog.Status.SENT : ImMessageLog.Status.FAILED);
        entry.setPlatformMsgId(channel.code() + ":out:" + System.nanoTime());
        entry.setPlatformConversationId(target.conversationId());
        entry.setPlatformUserId(target.platformUserId());
        entry.setBoundUsername(boundUsername);
        entry.setContent(truncate(content));
        entry.setErrorMsg(errorMsg == null ? null : truncate(errorMsg, 500));
        save(entry);
    }

    private boolean save(ImMessageLog entry) {
        try {
            logRepository.save(entry);
            return true;
        } catch (DataIntegrityViolationException e) {
            logger.debug("重复 IM 消息已忽略: {}", entry.getPlatformMsgId());
            return false;
        }
    }

    private String truncate(String value) {
        return truncate(value, CONTENT_PREVIEW_LIMIT);
    }

    private String truncate(String value, int limit) {
        if (value == null) return null;
        return value.length() <= limit ? value : value.substring(0, limit) + "...";
    }
}
