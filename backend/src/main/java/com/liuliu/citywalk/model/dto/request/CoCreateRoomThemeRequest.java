package com.liuliu.citywalk.model.dto.request;

import java.util.List;

public record CoCreateRoomThemeRequest(
        String title,
        String description,
        String category,
        List<String> missions,
        String vibeColor,
        String provider,
        String coverImageUrl
) {
}
