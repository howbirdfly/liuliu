package com.liuliu.citywalk.model.dto.response;

import java.util.List;

public record CommunityCommentResponse(
        Long id,
        Long walkId,
        Long parentId,
        Long authorId,
        String authorNickname,
        String authorAvatar,
        String content,
        Boolean deleted,
        Long createdAt,
        List<CommunityCommentResponse> replies
) {
}
