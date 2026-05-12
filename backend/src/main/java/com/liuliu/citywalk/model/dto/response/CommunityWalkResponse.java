package com.liuliu.citywalk.model.dto.response;

import java.util.List;

public record CommunityWalkResponse(
        Long id,
        String themeTitle,
        String themeCategory,
        String locationName,
        Long authorId,
        String authorNickname,
        String authorAvatar,
        String recordUnit,
        Boolean isPublic,
        String noteText,
        String photoUrl,
        List<?> path,
        List<?> completedMissions,
        Long likeCount,
        Long favoriteCount,
        Long viewCount,
        List<String> tags,
        Long createdAt
) {
}
