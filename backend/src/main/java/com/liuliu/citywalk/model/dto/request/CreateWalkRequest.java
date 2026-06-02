package com.liuliu.citywalk.model.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CreateWalkRequest(
        @NotBlank(message = "主题标题不能为空")
        String themeTitle,
        String themeCategory,
        String locationName,
        @NotBlank(message = "记录方式不能为空")
        String recordUnit,
        Boolean isPublic,
        String noteText,
        @Valid
        List<PathPointRequest> path,
        @Valid
        List<CompletedMissionRequest> completedMissions,
        List<String> tags,
        String roomCode,
        @Valid
        List<RoomMemberTrackRequest> roomMembers,
        String photoUrl,
        String videoUrl,
        String audioUrl
) {
}
