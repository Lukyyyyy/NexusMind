package com.luky.nexusmind.im.dispatch;

import com.luky.nexusmind.im.model.ImInboundMessage;
import com.luky.nexusmind.im.service.ImMessageLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * IM 入站消息消费者。业务异常在分发器内部已尽量兜底回复；
 * 这里再捕获一次并吞掉（不重试），避免重试导致重复回答——
 * 平台重推的重复消息已由 im_message_log 唯一索引幂等去重。
 */
@Component
public class ImInboundConsumer {

    private static final Logger logger = LoggerFactory.getLogger(ImInboundConsumer.class);

    private final ImMessageLogService messageLogService;
    private final ImDispatcher dispatcher;

    public ImInboundConsumer(ImMessageLogService messageLogService, ImDispatcher dispatcher) {
        this.messageLogService = messageLogService;
        this.dispatcher = dispatcher;
    }

    @KafkaListener(topics = "#{imKafkaConfig.getInboundTopic()}",
            groupId = "${spring.kafka.consumer.im-group-id:nexusmind-im-group}",
            containerFactory = "imKafkaListenerContainerFactory")
    public void onInbound(ImInboundMessage message) {
        if (message == null || message.dedupeKey() == null) {
            logger.warn("收到无效 IM 消息，已丢弃");
            return;
        }
        if (!messageLogService.recordInbound(message)) {
            logger.info("重复 IM 消息已忽略: {}", message.dedupeKey());
            return;
        }
        try {
            dispatcher.dispatch(message);
        } catch (Exception e) {
            logger.error("IM 消息分发失败: {}, 原因: {}", message.dedupeKey(), e.getMessage(), e);
        }
    }
}
