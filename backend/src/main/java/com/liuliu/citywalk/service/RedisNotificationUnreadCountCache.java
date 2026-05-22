package com.liuliu.citywalk.service;

import com.liuliu.citywalk.config.NotificationCacheProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@ConditionalOnProperty(prefix = "liuliu.redis.notification-cache", name = "enabled", havingValue = "true")
public class RedisNotificationUnreadCountCache implements NotificationUnreadCountCache {

    private static final Logger log = LoggerFactory.getLogger(RedisNotificationUnreadCountCache.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final NotificationCacheProperties properties;

    public RedisNotificationUnreadCountCache(
            StringRedisTemplate stringRedisTemplate,
            NotificationCacheProperties properties
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
    }

    @Override
    public Long get(Long userId) {
        if (!isValidUserId(userId)) {
            return null;
        }

        try {
            String value = stringRedisTemplate.opsForValue().get(buildKey(userId));
            if (value == null || value.isBlank()) {
                return null;
            }
            return Long.parseLong(value.trim());
        } catch (Exception error) {
            log.warn("Read notification unread count cache failed, userId={}", userId, error);
            return null;
        }
    }

    @Override
    public void put(Long userId, long unreadCount) {
        if (!isValidUserId(userId)) {
            return;
        }

        try {
            stringRedisTemplate.opsForValue().set(
                    buildKey(userId),
                    String.valueOf(Math.max(0L, unreadCount)),
                    Duration.ofSeconds(Math.max(60L, properties.getTtlSeconds()))
            );
        } catch (Exception error) {
            log.warn("Write notification unread count cache failed, userId={}", userId, error);
        }
    }

    @Override
    public void incrementIfPresent(Long userId, long delta) {
        if (!isValidUserId(userId)) {
            return;
        }

        String key = buildKey(userId);
        try {
            Boolean present = stringRedisTemplate.hasKey(key);
            if (!Boolean.TRUE.equals(present)) {
                return;
            }

            Long nextValue = stringRedisTemplate.opsForValue().increment(key, delta);
            if (nextValue != null && nextValue < 0) {
                put(userId, 0L);
            }
            stringRedisTemplate.expire(key, Duration.ofSeconds(Math.max(60L, properties.getTtlSeconds())));
        } catch (Exception error) {
            log.warn("Increment notification unread count cache failed, userId={}, delta={}", userId, delta, error);
        }
    }

    private String buildKey(Long userId) {
        return properties.getKeyPrefix() + userId;
    }

    private boolean isValidUserId(Long userId) {
        return userId != null && userId > 0;
    }
}
