package com.liuliu.citywalk.model.dto.response;

import java.util.List;

public record WalkResponse(
        Long id,
        String themeTitle,
        String themeCategory,
        WalkThemeSnapshotResponse theme,
        String locationName,
        Long authorId,
        String authorNickname,
        String authorAvatar,
        String recordUnit,
        Boolean isPublic,
        String noteText,
        String photoUrl,
        String videoUrl,
        String audioUrl,
        List<?> path,
        List<?> completedMissions,
        String roomCode,
        List<RoomMemberTrackResponse> roomMembers,
        List<String> tags,
        Long createdAt
) {
}
