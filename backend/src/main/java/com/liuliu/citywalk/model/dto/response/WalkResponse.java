package com.liuliu.citywalk.model.dto.response;

import java.util.List;

public record WalkResponse(
        Long id,
        String themeTitle,
        String themeCategory,
        String locationName,
        String recordUnit,
        Boolean isPublic,
        String noteText,
        String photoUrl,
        String videoUrl,
        String audioUrl,
        List<?> path,
        List<?> completedMissions,
        Long createdAt
) {
}
