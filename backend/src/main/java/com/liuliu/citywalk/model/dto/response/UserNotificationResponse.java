package com.liuliu.citywalk.model.dto.response;

public record UserNotificationResponse(
        Long id,
        String type,
        Long actorUserId,
        String actorNickname,
        String actorAvatar,
        Long walkId,
        String walkTitle,
        Long commentId,
        String commentContent,
        Boolean read,
        Long createdAt
) {
}
