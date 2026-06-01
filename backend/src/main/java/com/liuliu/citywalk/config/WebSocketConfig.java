package com.liuliu.citywalk.config;

import com.liuliu.citywalk.websocket.CoCreateRoomWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final CoCreateRoomWebSocketHandler coCreateRoomWebSocketHandler;

    public WebSocketConfig(CoCreateRoomWebSocketHandler coCreateRoomWebSocketHandler) {
        this.coCreateRoomWebSocketHandler = coCreateRoomWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(coCreateRoomWebSocketHandler, "/ws/co-create")
                .setAllowedOriginPatterns("*");
    }
}
