package com.luky.nexusmind.config;

import com.luky.nexusmind.handler.ChatWebSocketHandler;
import com.luky.nexusmind.handler.NotificationWebSocketHandler;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import com.luky.nexusmind.service.NotificationService;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private ChatWebSocketHandler chatWebSocketHandler;

    @Autowired
    private NotificationWebSocketHandler notificationWebSocketHandler;

    @Value("${app.websocket-allowed-origins:}")
    private String websocketAllowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/chat/{ticket}")
                .setAllowedOriginPatterns(websocketAllowedOrigins.split(","));
        registry.addHandler(notificationWebSocketHandler, "/notifications/{ticket}")
                .setAllowedOriginPatterns(websocketAllowedOrigins.split(","));
    }

    @Bean
    RedisMessageListenerContainer notificationListenerContainer(RedisConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(notificationWebSocketHandler, new ChannelTopic(NotificationService.CHANNEL));
        return container;
    }
}
