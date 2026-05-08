package com.liuliu.citywalk.model.dto.response;

public record UserProfileResponse(
        Long id,
        String nickname,
        String avatar,
        String bio
) {
}
