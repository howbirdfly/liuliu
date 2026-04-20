package com.liuliu.citywalk.model.dto.response;

import java.util.List;

public record CoCreateRoomResponse(
        String roomCode,
        Long ownerUserId,
        Integer memberLimit,
        CoCreateRoomThemeResponse theme,
        List<CoCreateRoomMemberResponse> members,
        Long createdAt
) {
}
