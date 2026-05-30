package com.liuliu.citywalk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.config.AgentMemoryProperties;
import com.liuliu.citywalk.service.agent.LlmMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
@ConditionalOnProperty(prefix = "liuliu.redis.agent-memory", name = "enabled", havingValue = "true")
public class RedisAgentMemoryService implements AgentMemoryService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentMemoryProperties properties;

    public RedisAgentMemoryService(
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
    public List<LlmMessage> loadConversation(Long userId) {
        if (userId == null || userId <= 0) {
            return List.of();
        }
        List<String> values = stringRedisTemplate.opsForList().range(buildKey(userId), 0, -1);
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        List<LlmMessage> messages = new ArrayList<>();
        for (String value : values) {
            ConversationTurn turn = readTurn(value);
            if (turn == null) {
                continue;
            }
            String userPrompt = normalize(turn.userPrompt());
            if (!userPrompt.isBlank()) {
                messages.add(LlmMessage.user(userPrompt));
            }
            String assistantAnswer = normalize(turn.assistantAnswer());
            if (!assistantAnswer.isBlank()) {
                messages.add(LlmMessage.assistant(assistantAnswer, List.of()));
            }
        }
        return messages;
    }

    @Override
    public void appendTurn(Long userId, String userPrompt, String assistantAnswer) {
        if (userId == null || userId <= 0) {
            return;
        }
        String normalizedPrompt = normalize(userPrompt);
        String normalizedAnswer = normalize(assistantAnswer);
        if (normalizedPrompt.isBlank() || normalizedAnswer.isBlank()) {
            return;
        }

        String key = buildKey(userId);
        String value = writeTurn(new ConversationTurn(
                normalizedPrompt,
                normalizedAnswer,
                System.currentTimeMillis()
        ));
        if (value == null) {
            return;
        }

        Long size = stringRedisTemplate.opsForList().rightPush(key, value);
        int maxTurns = Math.max(1, properties.getMaxTurns());
        if (size != null && size > maxTurns) {
            long start = Math.max(0L, size - maxTurns);
            stringRedisTemplate.opsForList().trim(key, start, -1);
        }
        stringRedisTemplate.expire(key, Duration.ofSeconds(Math.max(300L, properties.getTtlSeconds())));
    }

    @Override
    public void clearConversation(Long userId) {
        if (userId == null || userId <= 0) {
            return;
        }
        stringRedisTemplate.delete(buildKey(userId));
    }

    private String buildKey(Long userId) {
        return properties.getKeyPrefix() + "user:" + userId;
    }

    private ConversationTurn readTurn(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(value, ConversationTurn.class);
        } catch (Exception error) {
            return null;
        }
    }

    private String writeTurn(ConversationTurn turn) {
        try {
            return objectMapper.writeValueAsString(turn);
        } catch (Exception error) {
            return null;
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private record ConversationTurn(
            String userPrompt,
            String assistantAnswer,
            Long createdAt
    ) {
    }
}
