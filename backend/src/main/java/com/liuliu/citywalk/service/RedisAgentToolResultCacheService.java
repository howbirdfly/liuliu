package com.liuliu.citywalk.service;

import com.liuliu.citywalk.config.AgentToolCacheProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

@Service
@ConditionalOnProperty(prefix = "liuliu.redis.agent-tool-cache", name = "enabled", havingValue = "true")
public class RedisAgentToolResultCacheService implements AgentToolResultCacheService {

    private final StringRedisTemplate stringRedisTemplate;
    private final AgentToolCacheProperties properties;

    public RedisAgentToolResultCacheService(
            StringRedisTemplate stringRedisTemplate,
            AgentToolCacheProperties properties
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String get(String invocationSignature) {
        if (invocationSignature == null || invocationSignature.isBlank()) {
            return null;
        }
        try {
            String value = stringRedisTemplate.opsForValue().get(buildKey(invocationSignature));
            return value == null || value.isBlank() ? null : value;
        } catch (Exception error) {
            return null;
        }
    }

    @Override
    public void put(String invocationSignature, String output) {
        if (invocationSignature == null || invocationSignature.isBlank() || output == null || output.isBlank()) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(
                    buildKey(invocationSignature),
                    output,
                    Duration.ofSeconds(Math.max(30L, properties.getTtlSeconds()))
            );
        } catch (Exception ignored) {
        }
    }

    private String buildKey(String invocationSignature) {
        return properties.getKeyPrefix() + sha256(invocationSignature);
    }

    private String sha256(String text) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] digest = messageDigest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                builder.append(Character.forDigit((value >>> 4) & 0xF, 16));
                builder.append(Character.forDigit(value & 0xF, 16));
            }
            return builder.toString();
        } catch (Exception error) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
