package com.trustmarket.game.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Client gửi lên server qua prefix /app
        config.setApplicationDestinationPrefixes("/app");
        // Server bắn về client qua prefix /topic (public) và /queue (private)
        config.enableSimpleBroker("/topic", "/queue");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Thêm .setAllowedOriginPatterns("*") vào giữa
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*") // <--- DÒNG QUAN TRỌNG NHẤT
                .withSockJS();
    }
}