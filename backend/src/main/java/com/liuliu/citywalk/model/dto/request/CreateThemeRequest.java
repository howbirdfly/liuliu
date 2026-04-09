package com.liuliu.citywalk.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record CreateThemeRequest(
        @NotBlank(message = "主题标题不能为空")
        String title,
        @NotBlank(message = "主题描述不能为空")
        String description,
        @NotBlank(message = "主题分类不能为空")
        String category,
        @NotEmpty(message = "任务列表不能为空")
        List<String> missions,
        String coverImageUrl
) {
}
