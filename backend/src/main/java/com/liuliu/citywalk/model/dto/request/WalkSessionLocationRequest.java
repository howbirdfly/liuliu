package com.liuliu.citywalk.model.dto.request;

public record WalkSessionLocationRequest(
        String name,
        Double lat,
        Double lng
) {
}
