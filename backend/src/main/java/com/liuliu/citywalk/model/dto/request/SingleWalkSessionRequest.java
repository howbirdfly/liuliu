package com.liuliu.citywalk.model.dto.request;

import jakarta.validation.Valid;

import java.util.List;

public record SingleWalkSessionRequest(
        String walkMode,
        @Valid WalkSessionThemeRequest theme,
        String noteText,
        List<String> checkedMissions,
        @Valid WalkSessionLocationRequest selectedLocation,
        List<PathPointRequest> path,
        Boolean isTracking,
        String locationContext,
        String searchLocation
) {
}
