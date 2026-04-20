package com.liuliu.citywalk.model.dto.request;

import jakarta.validation.Valid;

import java.util.List;

public record UpdateCoCreateRoomStateRequest(
        Boolean isTracking,
        @Valid
        PathPointRequest currentPosition,
        @Valid
        List<PathPointRequest> path,
        List<String> completedMissions
) {
}
