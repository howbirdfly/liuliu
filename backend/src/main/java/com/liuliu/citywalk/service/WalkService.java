package com.liuliu.citywalk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.mapper.UserMapper;
import com.liuliu.citywalk.mapper.WalkRecordMapper;
import com.liuliu.citywalk.mapper.entity.WalkRecordEntity;
import com.liuliu.citywalk.model.dto.request.CompletedMissionRequest;
import com.liuliu.citywalk.model.dto.request.CreateWalkRequest;
import com.liuliu.citywalk.model.dto.request.PathPointRequest;
import com.liuliu.citywalk.model.dto.response.WalkResponse;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class WalkService {

    private final WalkRecordMapper walkRecordMapper;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    public WalkService(WalkRecordMapper walkRecordMapper, UserMapper userMapper, ObjectMapper objectMapper) {
        this.walkRecordMapper = walkRecordMapper;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
    }

    public WalkResponse create(Long userId, CreateWalkRequest request) {
        ensureUserExists(userId);
        List<String> completedMissions = normalizeCompletedMissions(request.completedMissions());
        List<Map<String, Object>> routePoints = normalizeRoutePoints(request.path());
        List<String> photoList = normalizePhotoList(request.photoUrl());
        String coverImage = photoList.isEmpty() ? "" : photoList.get(0);

        WalkRecordEntity entity = new WalkRecordEntity();
        entity.setUserId(userId);
        entity.setThemeTitle(safeText(request.themeTitle(), "鍩庡競婕"));
        entity.setThemeSnapshot(writeJson(buildThemeSnapshot(request, completedMissions)));
        entity.setLocationName(safeText(request.locationName(), "褰撳墠浣嶇疆"));
        entity.setLocationContext("");
        entity.setRoutePoints(writeJson(routePoints));
        entity.setMissionsCompleted(writeJson(completedMissions));
        entity.setMissionReviews(writeJson(Map.of()));
        entity.setPhotoList(writeJson(photoList));
        entity.setCoverImage(coverImage);
        entity.setNoteText(safeText(request.noteText(), ""));
        entity.setIsPublic(Boolean.TRUE.equals(request.isPublic()));
        entity.setWalkMode("web");
        entity.setGenerationSource("web-debug");
        entity.setStatus("active");
        walkRecordMapper.insert(entity);

        if (entity.getId() == null) {
            throw new IllegalStateException("failed_to_create_walk");
        }
        return getDetail(entity.getId());
    }

    public List<WalkResponse> listMyWalks(Long userId, int limit) {
        return walkRecordMapper.findMyActive(userId, limit).stream()
                .map(this::toWalkResponse)
                .toList();
    }

    public List<WalkResponse> listPublicWalks(int limit) {
        return walkRecordMapper.findPublicActive(limit).stream()
                .map(this::toWalkResponse)
                .toList();
    }

    public WalkResponse getDetail(Long id) {
        return Optional.ofNullable(walkRecordMapper.findActiveById(id)).map(this::toWalkResponse).orElse(null);
    }

    private WalkResponse toWalkResponse(WalkRecordEntity entity) {
        Map<String, Object> snapshot = parseJson(entity.getThemeSnapshot(), new TypeReference<Map<String, Object>>() { }, Map.of());
        String themeCategory = snapshot.get("category") instanceof String value ? value : null;
        List<Map<String, Object>> routePoints = parseJson(entity.getRoutePoints(), new TypeReference<List<Map<String, Object>>>() { }, List.of());
        List<String> completedMissions = parseJson(entity.getMissionsCompleted(), new TypeReference<List<String>>() { }, List.of());
        List<String> photoList = parseJson(entity.getPhotoList(), new TypeReference<List<String>>() { }, List.of());
        String photoUrl = !photoList.isEmpty() ? photoList.get(0) : entity.getCoverImage();
        String recordUnit = photoUrl != null && !photoUrl.isBlank()
                ? "image"
                : !routePoints.isEmpty()
                ? "location"
                : "event";
        return new WalkResponse(
                entity.getId(),
                entity.getThemeTitle(),
                themeCategory,
                entity.getLocationName(),
                recordUnit,
                entity.getIsPublic(),
                entity.getNoteText(),
                photoUrl,
                null,
                null,
                routePoints,
                completedMissions,
                toEpochMilli(entity.getCreatedAt())
        );
    }

    private Map<String, Object> buildThemeSnapshot(CreateWalkRequest request, List<String> missions) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("title", safeText(request.themeTitle(), "鍩庡競婕"));
        snapshot.put("description", safeText(request.noteText(), ""));
        snapshot.put("category", safeText(request.themeCategory(), ""));
        snapshot.put("missions", missions);
        snapshot.put("vibeColor", "#5a5a40");
        snapshot.put("provider", "web-debug");
        return snapshot;
    }

    private List<String> normalizeCompletedMissions(List<CompletedMissionRequest> missions) {
        if (missions == null || missions.isEmpty()) {
            return List.of();
        }
        return missions.stream()
                .map(CompletedMissionRequest::mission)
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .toList();
    }

    private List<Map<String, Object>> normalizeRoutePoints(List<PathPointRequest> points) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        return points.stream()
                .map(point -> Map.<String, Object>of(
                        "lat", point.lat(),
                        "lng", point.lng(),
                        "timestamp", point.timestamp()
                ))
                .collect(Collectors.toList());
    }

    private List<String> normalizePhotoList(String photoUrl) {
        if (photoUrl == null || photoUrl.isBlank()) {
            return List.of();
        }
        return List.of(photoUrl.trim());
    }

    private String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("json_write_failed", error);
        }
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

    private void ensureUserExists(Long userId) {
        if (userId == null || userId <= 0) {
            return;
        }
        Integer count = userMapper.countById(userId);
        if (count != null && count > 0) {
            return;
        }
        userMapper.insertDebugUser(userId, "web_debug_" + userId, "Web Debug User", "", "web");
    }

    private Long toEpochMilli(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toEpochMilli();
    }
}
