package com.luky.nexusmind.im.gateway;

import com.luky.nexusmind.im.channel.ImChannelAdapter;
import com.luky.nexusmind.im.channel.ImChannelConfigService;
import com.luky.nexusmind.im.channel.ImChannelRegistry;
import com.luky.nexusmind.im.channel.ImChannelRuntime;
import com.luky.nexusmind.im.dispatch.ImInboundProducer;
import com.luky.nexusmind.im.model.ImChannelType;
import com.luky.nexusmind.im.model.ImInboundMessage;
import com.luky.nexusmind.im.model.ImRawEvent;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IM 渠道回调统一入口（SecurityConfig 中对本路径 permitAll，安全性由各渠道验签保证）。
 * 只做验签、归一化与投递，不在请求线程内执行问答，快速 ACK 以满足平台超时约束。
 */
@RestController
@RequestMapping("/api/v1/im/callback")
public class ImCallbackController {

    private static final Logger logger = LoggerFactory.getLogger(ImCallbackController.class);

    private final ImChannelRegistry registry;
    private final ImChannelConfigService configService;
    private final ImInboundProducer producer;

    public ImCallbackController(ImChannelRegistry registry,
                                ImChannelConfigService configService,
                                ImInboundProducer producer) {
        this.registry = registry;
        this.configService = configService;
        this.producer = producer;
    }

    /** 部分开放平台用 GET 查询参数做 URL 归属验证（企微 echostr 等）。
     *  多用户模式下，尝试所有启用的该渠道配置，任一验证通过即可。 */
    @GetMapping("/{channel}")
    public ResponseEntity<String> verifyUrl(@PathVariable String channel, HttpServletRequest request) {
        ImChannelType type = ImChannelType.fromCode(channel);
        if (type == null || !registry.supports(type)) {
            return ResponseEntity.notFound().build();
        }
        ImRawEvent rawEvent = toRawEvent(type, request, "");
        // 尝试所有启用的该渠道配置
        for (ImChannelRuntime runtime : configService.listEnabledRuntimes(type)) {
            try {
                String echo = registry.require(type).verifyChallenge(runtime, rawEvent);
                if (echo != null) return ResponseEntity.ok(echo);
            } catch (Exception e) {
                logger.debug("URL 验证失败(user={}): {}", runtime.userId(), e.getMessage());
            }
        }
        logger.warn("IM 渠道 URL 验证失败({}): 无任何配置匹配", channel);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    @PostMapping("/{channel}")
    public ResponseEntity<Map<String, Object>> onCallback(@PathVariable String channel,
                                                          @RequestBody(required = false) String body,
                                                          HttpServletRequest request) {
        ImChannelType type = ImChannelType.fromCode(channel);
        if (type == null || !registry.supports(type)) {
            return ResponseEntity.notFound().build();
        }
        ImChannelAdapter adapter = registry.require(type);
        ImRawEvent rawEvent = toRawEvent(type, request, body == null ? "" : body);

        // 多用户模式：先归一化提取 botId，再找对应用户的配置进行验签
        ImInboundMessage message = adapter.normalize(rawEvent);
        if (message == null) {
            return ResponseEntity.ok(Map.of("code", 200, "message", "ignored"));
        }

        // 从消息找到对应的配置（按 botId）
        ImChannelRuntime runtime = configService.findRuntimeByBotId(type, message);
        if (runtime == null) {
            logger.warn("IM 回调找不到对应的机器人配置，渠道: {}, botId 从消息提取失败", channel);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("code", 503, "message", "bot not found"));
        }

        // 验签
        if (!adapter.verify(runtime, rawEvent)) {
            logger.warn("IM 回调验签失败，渠道: {}, userId: {}", channel, runtime.userId());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("code", 403, "message", "invalid signature"));
        }

        if (!producer.publish(message)) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "enqueue failed"));
        }
        return ResponseEntity.ok(Map.of("code", 200, "message", "ok"));
    }

    private ImRawEvent toRawEvent(ImChannelType type, HttpServletRequest request, String body) {
        Map<String, String> headers = new LinkedHashMap<>();
        Collections.list(request.getHeaderNames())
                .forEach(name -> headers.putIfAbsent(name, request.getHeader(name)));
        return new ImRawEvent(type.code(), headers, body);
    }
}
