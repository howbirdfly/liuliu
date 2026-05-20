package com.liuliu.citywalk.model.dto.request;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record UpdateWalkRequest(
        @NotBlank(message = "帖子标题不能为空")
        String themeTitle,
        String themeCategory,
        Boolean isPublic,
        String noteText,
        List<String> tags
) {
}
