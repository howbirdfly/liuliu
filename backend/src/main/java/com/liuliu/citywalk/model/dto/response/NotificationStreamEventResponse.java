package com.liuliu.citywalk.model.dto.response;

public record NotificationStreamEventResponse(
        String type,
        Long unreadCount,
        UserNotificationResponse notification
) {
}
