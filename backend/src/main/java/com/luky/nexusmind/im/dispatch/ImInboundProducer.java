package com.luky.nexusmind.im.dispatch;

import com.luky.nexusmind.im.config.ImKafkaConfig;
import com.luky.nexusmind.im.model.ImInboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** 回调端点验证通过后把统一消息投递到 Kafka，快速 ACK 平台重试机制。 */
@Component
public class ImInboundProducer {

    private static final Logger logger = LoggerFactory.getLogger(ImInboundProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ImKafkaConfig imKafkaConfig;

    public ImInboundProducer(KafkaTemplate<String, Object> kafkaTemplate, ImKafkaConfig imKafkaConfig) {
        this.kafkaTemplate = kafkaTemplate;
        this.imKafkaConfig = imKafkaConfig;
    }

    /** 阻塞确认投递结果；失败返回 false，让平台按其重试策略重推。 */
    public boolean publish(ImInboundMessage message) {
        try {
            kafkaTemplate.executeInTransaction(operations -> {
                operations.send(imKafkaConfig.getInboundTopic(), message.dedupeKey(), message);
                return true;
            });
            return true;
        } catch (Exception e) {
            logger.error("IM 入站消息投递 Kafka 失败: {}, 原因: {}", message.dedupeKey(), e.getMessage());
            return false;
        }
    }
}
