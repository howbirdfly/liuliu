package com.liuliu.citywalk.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.mapper.CommunityMapper;
import com.liuliu.citywalk.mapper.WalkInteractionMapper;
import com.liuliu.citywalk.mapper.WalkRecordMapper;
import com.liuliu.citywalk.mapper.entity.CommunityWalkQueryRow;
import com.liuliu.citywalk.model.dto.response.CommunityEngagementResponse;
import com.liuliu.citywalk.model.dto.response.CommunityWalkResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Service
public class CommunityService {

    private static final String TAG_SEPARATOR = "\\|\\|";
    private static final String DEFAULT_AUTHOR_NAME = "Community Walker";

    private final CommunityMapper communityMapper;
    private final WalkRecordMapper walkRecordMapper;
    private final WalkInteractionMapper walkInteractionMapper;
    private final ObjectMapper objectMapper;

    public CommunityService(
            CommunityMapper communityMapper,
            WalkRecordMapper walkRecordMapper,
            WalkInteractionMapper walkInteractionMapper,
            ObjectMapper objectMapper
    ) {
        this.communityMapper = communityMapper;
        this.walkRecordMapper = walkRecordMapper;
        this.walkInteractionMapper = walkInteractionMapper;
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
}
