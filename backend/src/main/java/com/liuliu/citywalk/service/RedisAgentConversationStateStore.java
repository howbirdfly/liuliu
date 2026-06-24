package com.liuliu.citywalk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.config.AgentMemoryProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@ConditionalOnProperty(prefix = "liuliu.redis.agent-memory", name = "enabled", havingValue = "true")
public class RedisAgentConversationStateStore implements AgentConversationStateStore {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentMemoryProperties properties;

    public RedisAgentConversationStateStore(
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper,
            AgentMemoryProperties properties
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public ConversationStateSnapshot loadState(Long userId) {
        if (userId == null || userId <= 0) {
            return null;
        }
        String value = stringRedisTemplate.opsForValue().get(buildKey(userId));
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(value, ConversationStateSnapshot.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public void saveState(Long userId, ConversationStateSnapshot snapshot) {
        if (userId == null || userId <= 0 || snapshot == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(
                    buildKey(userId),
                    objectMapper.writeValueAsString(snapshot),
                    Duration.ofSeconds(Math.max(300L, properties.getTtlSeconds()))
            );
        } catch (Exception ignored) {
        }
    }

    @Override
    public void clearState(Long userId) {
        if (userId == null || userId <= 0) {
            return;
        }
        stringRedisTemplate.delete(buildKey(userId));
    }

    private String buildKey(Long userId) {
        return properties.getKeyPrefix() + "state:user:" + userId;
    }
}
