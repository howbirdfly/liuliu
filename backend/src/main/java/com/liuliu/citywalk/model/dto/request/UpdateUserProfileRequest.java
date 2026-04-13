package com.liuliu.citywalk.model.dto.request;

import jakarta.validation.constraints.NotBlank;

public record UpdateUserProfileRequest(
        @NotBlank(message = "昵称不能为空")
        String nickname,
        String avatar
) {
}
