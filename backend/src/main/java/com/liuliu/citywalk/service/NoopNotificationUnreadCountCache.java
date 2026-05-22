package com.liuliu.citywalk.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(
        prefix = "liuliu.redis.notification-cache",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true
)
public class NoopNotificationUnreadCountCache implements NotificationUnreadCountCache {

    @Override
    public Long get(Long userId) {
        return null;
    }

    @Override
    public void put(Long userId, long unreadCount) {
        // Intentionally empty. When Redis cache is disabled, the notification service falls back to DB.
    }

    @Override
    public void incrementIfPresent(Long userId, long delta) {
        // Intentionally empty. Cache miss will fall back to DB and refresh lazily.
    }
}
