package com.liuliu.citywalk.model.dto.response;

public record CoCreateRoomChatMessageResponse(
        String messageId,
        Long userId,
        String nickname,
        String avatarUrl,
        String content,
        Long sentAt
) {
}