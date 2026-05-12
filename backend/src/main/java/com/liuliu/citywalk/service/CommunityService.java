package com.liuliu.citywalk.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.mapper.CommunityMapper;
import com.liuliu.citywalk.mapper.entity.CommunityWalkQueryRow;
import com.liuliu.citywalk.model.dto.response.CommunityWalkResponse;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Service
public class CommunityService {

    private static final String TAG_SEPARATOR = "\\|\\|";
    private static final String DEFAULT_AUTHOR_NAME = "Community Walker";

    private final CommunityMapper communityMapper;
    private final ObjectMapper objectMapper;

    public CommunityService(CommunityMapper communityMapper, ObjectMapper objectMapper) {
        this.communityMapper = communityMapper;
        this.objectMapper = objectMapper;
    }

    public List<CommunityWalkResponse> searchWalks(String keyword, int page, int pageSize) {
        int limit = normalizePageSize(pageSize);
        int offset = normalizeOffset(page, limit);
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        List<CommunityWalkQueryRow> rows = normalizedKeyword.isEmpty()
                ? communityMapper.listLatestPublicWalks(limit, offset)
                : communityMapper.searchPublicWalks(normalizedKeyword, limit, offset);
        return rows.stream().map(this::toResponse).toList();
    }

    public List<CommunityWalkResponse> latestFeed(int page, int pageSize) {
        int limit = normalizePageSize(pageSize);
        int offset = normalizeOffset(page, limit);
        return communityMapper.listLatestPublicWalks(limit, offset).stream().map(this::toResponse).toList();
    }

    public List<CommunityWalkResponse> hotFeed(int page, int pageSize) {
        int limit = normalizePageSize(pageSize);
        int offset = normalizeOffset(page, limit);
        return communityMapper.listHotPublicWalks(limit, offset).stream().map(this::toResponse).toList();
    }

    public List<CommunityWalkResponse> recommendFeed(int page, int pageSize) {
        int limit = normalizePageSize(pageSize);
        int offset = normalizeOffset(page, limit);
        return communityMapper.listRecommendedPublicWalks(limit, offset).stream().map(this::toResponse).toList();
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
                parseTags(row.getTags()),
                toEpochMilli(row.getCreatedAt())
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
}
