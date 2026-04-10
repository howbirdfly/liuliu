package com.liuliu.citywalk.model.dto.request;

import jakarta.validation.constraints.NotBlank;

public record GenerateWalkRecordCardRequest(
        @NotBlank(message = "主题标题不能为空")
        String themeTitle,
        String themeDescription,
        @NotBlank(message = "任务内容不能为空")
        String missionText,
        @NotBlank(message = "地点名称不能为空")
        String locationName,
        @NotBlank(message = "地点环境不能为空")
        String locationContext,
        String noteText,
        boolean hasPhoto
) {
}
