package com.liuliu.citywalk.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.mapper.CommunityCommentMapper;
import com.liuliu.citywalk.mapper.CommunityMapper;
import com.liuliu.citywalk.mapper.WalkInteractionMapper;
import com.liuliu.citywalk.mapper.WalkRecordMapper;
import com.liuliu.citywalk.mapper.entity.CommunityCommentEntity;
import com.liuliu.citywalk.mapper.entity.CommunityCommentQueryRow;
import com.liuliu.citywalk.mapper.entity.CommunityWalkQueryRow;
import com.liuliu.citywalk.model.dto.request.CommunityCommentCreateRequest;
import com.liuliu.citywalk.model.dto.response.CommunityCommentResponse;
import com.liuliu.citywalk.model.dto.response.CommunityEngagementResponse;
import com.liuliu.citywalk.model.dto.response.CommunityWalkResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class CommunityService {

    private static final String TAG_SEPARATOR = "\\|\\|";
    private static final String DEFAULT_AUTHOR_NAME = "Community Walker";

    private final CommunityCommentMapper communityCommentMapper;
    private final CommunityMapper communityMapper;
    private final WalkRecordMapper walkRecordMapper;
    private final WalkInteractionMapper walkInteractionMapper;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public CommunityService(
            CommunityCommentMapper communityCommentMapper,
            CommunityMapper communityMapper,
            WalkRecordMapper walkRecordMapper,
            WalkInteractionMapper walkInteractionMapper,
            NotificationService notificationService,
            ObjectMapper objectMapper
    ) {
        this.communityCommentMapper = communityCommentMapper;
        this.communityMapper = communityMapper;
        this.walkRecordMapper = walkRecordMapper;
        this.walkInteractionMapper = walkInteractionMapper;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    public List<CommunityWalkResponse> searchWalks(String keyword, Long currentUserId, int page, int pageSize) {
        int limit = normalizePageSize(pageSize);
        int offset = normalizeOffset(page, limit);
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        List<CommunityWalkQueryRow> rows = normalizedKeyword.isEmpty()
                ? communityMapper.listLatestPublicWalks(currentUserId, limit, offset)
                : communityMapper.searchPublicWalks(normalizedKeyword, currentUserId, limit, offset);
        return rows.stream().map(this::toResponse).toList();
    }

    public List<CommunityWalkResponse> latestFeed(Long currentUserId, int page, int pageSize) {
        int limit = normalizePageSize(pageSize);
        int offset = normalizeOffset(page, limit);
        return communityMapper.listLatestPublicWalks(currentUserId, limit, offset).stream().map(this::toResponse).toList();
    }

    public List<CommunityWalkResponse> hotFeed(Long currentUserId, int page, int pageSize) {
        int limit = normalizePageSize(pageSize);
        int offset = normalizeOffset(page, limit);
        return communityMapper.listHotPublicWalks(currentUserId, limit, offset).stream().map(this::toResponse).toList();
    }

    public List<CommunityWalkResponse> recommendFeed(Long currentUserId, int page, int pageSize) {
        int limit = normalizePageSize(pageSize);
        int offset = normalizeOffset(page, limit);
        return communityMapper.listRecommendedPublicWalks(currentUserId, limit, offset).stream().map(this::toResponse).toList();
    }

    public List<CommunityWalkResponse> likedWalks(Long currentUserId, int page, int pageSize) {
        int limit = normalizePageSize(pageSize);
        int offset = normalizeOffset(page, limit);
        return communityMapper.listLikedWalks(currentUserId, limit, offset).stream().map(this::toResponse).toList();
    }

    public List<CommunityWalkResponse> favoritedWalks(Long currentUserId, int page, int pageSize) {
        int limit = normalizePageSize(pageSize);
        int offset = normalizeOffset(page, limit);
        return communityMapper.listFavoritedWalks(currentUserId, limit, offset).stream().map(this::toResponse).toList();
    }

    @Transactional
    public CommunityWalkResponse getWalkDetail(Long walkId, Long currentUserId) {
        ensurePublicWalkExists(walkId);
        walkInteractionMapper.incrementViewCount(walkId);
        CommunityWalkQueryRow row = communityMapper.findPublicWalkById(walkId, currentUserId);
        if (row == null) {
            throw new IllegalStateException("walk_not_found");
        }
        return toResponse(row);
    }

    @Transactional
    public CommunityEngagementResponse likeWalk(Long walkId, Long userId) {
        ensurePublicWalkExists(walkId);
        if (!hasLike(walkId, userId)) {
            walkInteractionMapper.insertLike(walkId, userId);
            walkInteractionMapper.incrementLikeCount(walkId);
            notificationService.notifyWalkLiked(walkId, userId);
        }
        return buildEngagementResponse(walkId, userId);
    }

    @Transactional
    public CommunityEngagementResponse unlikeWalk(Long walkId, Long userId) {
        ensurePublicWalkExists(walkId);
        if (hasLike(walkId, userId)) {
            walkInteractionMapper.deleteLike(walkId, userId);
            walkInteractionMapper.decrementLikeCount(walkId);
        }
        return buildEngagementResponse(walkId, userId);
    }

    @Transactional
    public CommunityEngagementResponse favoriteWalk(Long walkId, Long userId) {
        ensurePublicWalkExists(walkId);
        if (!hasFavorite(walkId, userId)) {
            walkInteractionMapper.insertFavorite(walkId, userId);
            walkInteractionMapper.incrementFavoriteCount(walkId);
            notificationService.notifyWalkFavorited(walkId, userId);
        }
        return buildEngagementResponse(walkId, userId);
    }

    @Transactional
    public CommunityEngagementResponse unfavoriteWalk(Long walkId, Long userId) {
        ensurePublicWalkExists(walkId);
        if (hasFavorite(walkId, userId)) {
            walkInteractionMapper.deleteFavorite(walkId, userId);
            walkInteractionMapper.decrementFavoriteCount(walkId);
        }
        return buildEngagementResponse(walkId, userId);
    }

    public List<CommunityCommentResponse> listComments(Long walkId) {
        ensurePublicWalkExists(walkId);
        List<CommunityCommentQueryRow> rows = communityCommentMapper.findVisibleByWalkId(walkId);
        return buildCommentTree(rows);
    }

    @Transactional
    public CommunityCommentResponse createComment(Long walkId, Long userId, CommunityCommentCreateRequest body) {
        ensurePublicWalkExists(walkId);
        String content = body.content() == null ? "" : body.content().trim();
        if (content.isBlank()) {
            throw new IllegalStateException("comment_content_invalid");
        }

        Long parentId = body.parentId();
        CommunityCommentQueryRow parent = null;
        if (parentId != null) {
            parent = communityCommentMapper.findActiveById(parentId);
            if (parent == null || !walkId.equals(parent.getWalkId())) {
                throw new IllegalStateException("invalid_parent_comment");
            }
        }

        CommunityCommentEntity entity = new CommunityCommentEntity();
        entity.setWalkId(walkId);
        entity.setParentId(parentId);
        entity.setUserId(userId);
        entity.setContent(content);
        entity.setStatus("active");
        communityCommentMapper.insert(entity);
        notificationService.notifyCommentCreated(walkId, userId, entity.getId(), parentId);

        CommunityCommentQueryRow created = communityCommentMapper.findActiveById(entity.getId());
        if (created == null) {
            throw new IllegalStateException("comment_not_found");
        }
        return toCommentResponse(created, List.of());
    }

    @Transactional
    public void deleteComment(Long commentId, Long userId) {
        CommunityCommentQueryRow comment = communityCommentMapper.findById(commentId);
        if (comment == null) {
            throw new IllegalStateException("comment_not_found");
        }
        if (!Objects.equals(comment.getUserId(), userId)) {
            throw new IllegalStateException("comment_forbidden");
        }
        if ("deleted".equalsIgnoreCase(comment.getStatus())) {
            return;
        }

        communityCommentMapper.softDelete(commentId);
    }

    private CommunityWalkResponse toResponse(CommunityWalkQueryRow row) {
        Map<String, Object> snapshot = parseJson(
                row.getThemeSnapshot(),
                new TypeReference<Map<String, Object>>() {
                },
                Map.of()
        );
        String themeCategory = snapshot.get("category") instanceof String value ? value : null;
        List<Map<String, Object>> routePoints = parseJson(
                row.getRoutePoints(),
                new TypeReference<List<Map<String, Object>>>() {
                },
                List.of()
        );
        List<String> completedMissions = parseJson(
                row.getMissionsCompleted(),
                new TypeReference<List<String>>() {
                },
                List.of()
        );
        String photoUrl = safeText(row.getCoverImage());
        String recordUnit = !photoUrl.isBlank()
                ? "image"
                : !routePoints.isEmpty()
                ? "location"
                : "event";

        return new CommunityWalkResponse(
                row.getId(),
                row.getThemeTitle(),
                themeCategory,
                row.getLocationName(),
                row.getUserId(),
                safeFallbackText(row.getAuthorNickname(), DEFAULT_AUTHOR_NAME),
                safeText(row.getAuthorAvatar()),
                recordUnit,
                row.getIsPublic(),
                safeText(row.getNoteText()),
                photoUrl,
                routePoints,
                completedMissions,
                longValue(row.getLikeCount()),
                longValue(row.getFavoriteCount()),
                longValue(row.getViewCount()),
                Boolean.TRUE.equals(row.getLiked()),
                Boolean.TRUE.equals(row.getFavorited()),
                parseTags(row.getTags()),
                toEpochMilli(row.getCreatedAt())
        );
    }

    private CommunityEngagementResponse buildEngagementResponse(Long walkId, Long userId) {
        return new CommunityEngagementResponse(
                walkId,
                longValue(walkInteractionMapper.findLikeCount(walkId)),
                longValue(walkInteractionMapper.findFavoriteCount(walkId)),
                hasLike(walkId, userId),
                hasFavorite(walkId, userId)
        );
    }

    private int normalizePageSize(int pageSize) {
        return Math.min(Math.max(pageSize, 1), 50);
    }

    private int normalizeOffset(int page, int limit) {
        int normalizedPage = Math.max(page, 1);
        return (normalizedPage - 1) * limit;
    }

    private Long longValue(Integer value) {
        return value == null ? 0L : value.longValue();
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String safeFallbackText(String value, String fallback) {
        String normalized = safeText(value);
        return normalized.isBlank() ? fallback : normalized;
    }

    private List<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return List.of(tags.split(TAG_SEPARATOR)).stream()
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
    }

    private <T> T parseJson(String json, TypeReference<T> typeReference, T fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(json, typeReference);
        } catch (Exception error) {
            return fallback;
        }
    }

    private Long toEpochMilli(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toEpochMilli();
    }

    private void ensurePublicWalkExists(Long walkId) {
        if (walkId == null || walkId <= 0 || walkRecordMapper.findPublicActiveById(walkId) == null) {
            throw new IllegalStateException("walk_not_found");
        }
    }

    private boolean hasLike(Long walkId, Long userId) {
        Integer count = walkInteractionMapper.countLike(walkId, userId);
        return count != null && count > 0;
    }

    private boolean hasFavorite(Long walkId, Long userId) {
        Integer count = walkInteractionMapper.countFavorite(walkId, userId);
        return count != null && count > 0;
    }

    private List<CommunityCommentResponse> buildCommentTree(List<CommunityCommentQueryRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<Long, CommunityCommentResponse> byId = new LinkedHashMap<>();
        List<CommunityCommentResponse> roots = new ArrayList<>();

        for (CommunityCommentQueryRow row : rows) {
            byId.put(row.getId(), toCommentResponse(row, new ArrayList<>()));
        }

        for (CommunityCommentQueryRow row : rows) {
            CommunityCommentResponse current = byId.get(row.getId());
            Long parentId = row.getParentId();
            if (parentId == null) {
                roots.add(current);
                continue;
            }

            CommunityCommentResponse parent = byId.get(parentId);
            if (parent == null) {
                roots.add(current);
                continue;
            }

            parent.replies().add(current);
        }

        for (CommunityCommentResponse item : byId.values()) {
            item.replies().sort(Comparator.comparing(CommunityCommentResponse::createdAt).thenComparing(CommunityCommentResponse::id));
        }
        roots.sort(Comparator.comparing(CommunityCommentResponse::createdAt).thenComparing(CommunityCommentResponse::id));
        return roots;
    }

    private CommunityCommentResponse toCommentResponse(CommunityCommentQueryRow row, List<CommunityCommentResponse> replies) {
        return new CommunityCommentResponse(
                row.getId(),
                row.getWalkId(),
                row.getParentId(),
                row.getUserId(),
                safeFallbackText(row.getAuthorNickname(), DEFAULT_AUTHOR_NAME),
                safeText(row.getAuthorAvatar()),
                isDeleted(row) ? "该评论已删除" : safeText(row.getContent()),
                isDeleted(row),
                toEpochMilli(row.getCreatedAt()),
                replies
        );
    }

    private boolean isDeleted(CommunityCommentQueryRow row) {
        return row != null && "deleted".equalsIgnoreCase(safeText(row.getStatus()));
    }
}
