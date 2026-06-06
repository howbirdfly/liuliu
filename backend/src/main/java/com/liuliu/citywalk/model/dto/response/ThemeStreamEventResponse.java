package com.liuliu.citywalk.model.dto.response;

public record ThemeStreamEventResponse(
        String type,
        String delta,
        ThemeResponse theme,
        String provider,
        String model
) {
}
