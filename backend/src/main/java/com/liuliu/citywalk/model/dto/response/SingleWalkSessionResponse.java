package com.liuliu.citywalk.model.dto.response;

import com.liuliu.citywalk.model.dto.request.PathPointRequest;

import java.util.List;

public record SingleWalkSessionResponse(
        String walkMode,
        WalkThemeSnapshotResponse theme,
        String noteText,
        List<String> checkedMissions,
        WalkSessionLocationResponse selectedLocation,
        List<PathPointRequest> path,
        Boolean isTracking,
        String locationContext,
        String searchLocation,
        Long updatedAt
) {
}
