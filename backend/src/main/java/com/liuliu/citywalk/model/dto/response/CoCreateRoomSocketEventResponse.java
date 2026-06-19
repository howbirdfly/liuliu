package com.liuliu.citywalk.model.dto.response;

public record CoCreateRoomSocketEventResponse(
        String type,
        String roomCode,
        CoCreateRoomResponse room,
        CoCreateRoomMemberResponse member,
        Long memberUserId,
        CoCreateRoomThemeResponse theme,
        CoCreateRoomChatMessageResponse chatMessage,
        Long ownerUserId
) {
}