package com.liuliu.citywalk.model.dto.response;

import com.liuliu.citywalk.model.dto.request.PathPointRequest;

import java.util.List;

public record RoomMemberTrackResponse(
        Long userId,
        String nickname,
        String trackColor,
        Boolean isOwner,
        Boolean isTracking,
        PathPointRequest currentPosition,
        List<PathPointRequest> path,
        List<String> completedMissions
) {
}
