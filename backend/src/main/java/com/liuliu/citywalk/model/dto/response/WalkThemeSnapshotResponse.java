package com.liuliu.citywalk.model.dto.response;

import java.util.List;

public record WalkThemeSnapshotResponse(
        String title,
        String description,
        String category,
        List<String> missions,
        String vibeColor,
        String provider,
        String coverImageUrl
) {
}
