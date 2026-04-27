package com.liuliu.citywalk.model.dto.request;

import java.util.List;

public record RoomMemberTrackRequest(
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
