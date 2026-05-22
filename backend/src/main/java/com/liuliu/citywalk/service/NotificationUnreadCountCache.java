package com.liuliu.citywalk.service;

public interface NotificationUnreadCountCache {

    Long get(Long userId);

    void put(Long userId, long unreadCount);

    void incrementIfPresent(Long userId, long delta);
}
