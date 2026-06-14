package com.liuliu.citywalk.config;

import com.liuliu.citywalk.service.CoCreateRoomRealtimeService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

@Configuration
public class CoCreateRoomRealtimeRedisConfig {

    @Bean
    public RedisMessageListenerContainer coCreateRoomRedisMessageListenerContainer(
            RedisConnectionFactory redisConnectionFactory,
            CoCreateRoomProperties coCreateRoomProperties,
            CoCreateRoomRealtimeService coCreateRoomRealtimeService
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisConnectionFactory);

        if (!coCreateRoomProperties.isClusterBroadcastEnabled()) {
            return container;
        }

        MessageListener listener = (Message message, byte[] pattern) -> {
            if (message == null || message.getBody() == null || message.getBody().length == 0) {
                return;
            }
            String payload = new String(message.getBody(), StandardCharsets.UTF_8);
            coCreateRoomRealtimeService.handleClusterBroadcast(payload);
        };
        container.addMessageListener(listener, new ChannelTopic(coCreateRoomProperties.getClusterBroadcastChannel()));
        return container;
    }
}
