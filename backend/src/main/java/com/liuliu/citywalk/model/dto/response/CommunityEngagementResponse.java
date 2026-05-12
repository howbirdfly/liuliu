package com.liuliu.citywalk.model.dto.response;

public record CommunityEngagementResponse(
        Long walkId,
        Long likeCount,
        Long favoriteCount,
        Boolean liked,
        Boolean favorited
) {
}
