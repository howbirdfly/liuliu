package com.liuliu.citywalk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.mapper.UserMapper;
import com.liuliu.citywalk.mapper.WalkRecordMapper;
import com.liuliu.citywalk.mapper.entity.WalkRecordEntity;
import com.liuliu.citywalk.model.dto.request.MiniappCreateWalkRequest;
import com.liuliu.citywalk.model.dto.request.MiniappMissionReviewItem;
import com.liuliu.citywalk.model.dto.request.MiniappRoutePointRequest;
import com.liuliu.citywalk.model.dto.request.MiniappThemeSnapshotRequest;
import com.liuliu.citywalk.model.dto.response.MiniappMissionReviewResponse;
import com.liuliu.citywalk.model.dto.response.MiniappRoutePointResponse;
import com.liuliu.citywalk.model.dto.response.MiniappThemeSnapshotResponse;
import com.liuliu.citywalk.model.dto.response.MiniappWalkRecordResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class MiniappWalkService {

    private final WalkRecordMapper walkRecordMapper;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    public MiniappWalkService(WalkRecordMapper walkRecordMapper, UserMapper userMapper, ObjectMapper objectMapper) {
        this.walkRecordMapper = walkRecordMapper;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public MiniappWalkRecordResponse create(Long userId, MiniappCreateWalkRequest request) {
        if (userId == null || userId <= 0) {
            throw new IllegalStateException("miniapp_login_required");
        }
        ensureUserExists(userId);
        List<String> photoList = normalizeStoredPhotoList(request.photoList());

        WalkRecordEntity entity = new WalkRecordEntity();
        entity.setUserId(userId);
        entity.setThemeTitle(request.themeSnapshot() != null ? safeText(request.themeSnapshot().title(), "城市漫步") : "城市漫步");
        entity.setThemeSnapshot(writeJson(toThemeSnapshot(request.themeSnapshot())));
        entity.setLocationName(safeText(request.locationName(), "当前位置"));
        entity.setLocationContext(safeText(request.locationContext(), "城市街道"));
        entity.setRoutePoints(writeJson(toRoutePoints(request.routePoints())));
        entity.setMissionsCompleted(writeJson(safeStringList(request.missionsCompleted())));
        entity.setMissionReviews(writeJson(toMissionReviews(request.missionReviews())));
        entity.setPhotoList(writeJson(photoList));
        entity.setCoverImage(photoList.isEmpty() ? "" : photoList.get(0));
        entity.setNoteText(safeText(request.noteText(), ""));
        entity.setIsPublic(Boolean.TRUE.equals(request.isPublic()));
        entity.setWalkMode(safeText(request.walkMode(), "pure"));
        entity.setGenerationSource(safeText(request.generationSource(), "backend"));
        entity.setStatus("active");
        walkRecordMapper.insert(entity);

        if (entity.getId() == null) {
            throw new IllegalStateException("failed_to_create_walk");
        }
        return getDetail(String.valueOf(entity.getId()), userId);
    }

    public List<MiniappWalkRecordResponse> listMyWalks(Long userId, int limit) {
        if (userId == null || userId <= 0) {
            return List.of();
        }
        return walkRecordMapper.findMyActive(userId, limit).stream()
                .map(this::toResponse)
                .toList();
    }

    public List<MiniappWalkRecordResponse> listPublicWalks(int limit) {
        return walkRecordMapper.findPublicActive(limit).stream()
                .map(this::toResponse)
                .toList();
    }

    public MiniappWalkRecordResponse getDetail(String id, Long currentUserId) {
        return Optional.ofNullable(walkRecordMapper.findActiveById(Long.parseLong(id)))
                .map(this::toResponse)
                .filter(record -> Boolean.TRUE.equals(record.isPublic()) || (currentUserId != null && currentUserId > 0 && record.userId().equals(currentUserId)))
                .orElse(null);
    }

    private MiniappWalkRecordResponse toResponse(WalkRecordEntity entity) {
        return new MiniappWalkRecordResponse(
                String.valueOf(entity.getId()),
                entity.getUserId(),
                entity.getThemeTitle(),
                parseThemeSnapshot(entity.getThemeSnapshot()),
                entity.getLocationName(),
                entity.getLocationContext(),
                parseRoutePoints(entity.getRoutePoints()),
                parseStringList(entity.getMissionsCompleted()),
                parseMissionReviews(entity.getMissionReviews()),
                parseStringList(entity.getPhotoList()),
                toPublicUrl(entity.getCoverImage()),
                entity.getNoteText(),
                entity.getIsPublic(),
                entity.getWalkMode(),
                entity.getGenerationSource(),
                toEpochMilli(entity.getCreatedAt())
        );
    }

    private MiniappThemeSnapshotResponse parseThemeSnapshot(String json) {
        try {
            return objectMapper.readValue(json, MiniappThemeSnapshotResponse.class);
        } catch (Exception error) {
            return new MiniappThemeSnapshotResponse("城市漫步", "", "探索", List.of(), "#5a5a40", "backend");
        }
    }

    private List<MiniappRoutePointResponse> parseRoutePoints(String json) {
        return parseJson(json, new TypeReference<List<MiniappRoutePointResponse>>() { }, List.of());
    }

    private List<String> parseStringList(String json) {
        return parseJson(json, new TypeReference<List<String>>() { }, List.of()).stream()
                .map(this::toPublicUrl)
                .toList();
    }

    private Map<String, MiniappMissionReviewResponse> parseMissionReviews(String json) {
        return parseJson(json, new TypeReference<Map<String, MiniappMissionReviewResponse>>() { }, Map.of());
    }

    private MiniappThemeSnapshotResponse toThemeSnapshot(MiniappThemeSnapshotRequest snapshot) {
        if (snapshot == null) {
            return new MiniappThemeSnapshotResponse("城市漫步", "", "探索", List.of(), "#5a5a40", "backend");
        }
        return new MiniappThemeSnapshotResponse(
                safeText(snapshot.title(), "城市漫步"),
                safeText(snapshot.description(), ""),
                safeText(snapshot.category(), "探索"),
                safeStringList(snapshot.missions()),
                safeText(snapshot.vibeColor(), "#5a5a40"),
                safeText(snapshot.provider(), "backend")
        );
    }

    private List<MiniappRoutePointResponse> toRoutePoints(List<MiniappRoutePointRequest> routePoints) {
        if (routePoints == null || routePoints.isEmpty()) {
            return List.of();
        }
        return routePoints.stream()
                .map(item -> new MiniappRoutePointResponse(item.latitude(), item.longitude(), item.timestamp()))
                .toList();
    }

    private Map<String, MiniappMissionReviewResponse> toMissionReviews(Map<String, MiniappMissionReviewItem> missionReviews) {
        if (missionReviews == null || missionReviews.isEmpty()) {
            return Map.of();
        }
        Map<String, MiniappMissionReviewResponse> result = new LinkedHashMap<>();
        missionReviews.forEach((key, value) -> result.put(
                key,
                new MiniappMissionReviewResponse(
                        value != null ? value.passed() : null,
                        value != null ? value.comment() : "",
                        value != null ? value.confidence() : "medium",
                        value != null ? value.reviewedAt() : null
                )
        ));
        return result;
    }

    private List<String> safeStringList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .toList();
    }

    private List<String> normalizeStoredPhotoList(List<String> values) {
        return safeStringList(values).stream()
                .map(this::toStoredPath)
                .toList();
    }

    private String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String toStoredPath(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        int uploadIndex = trimmed.indexOf("/uploads/");
        if (uploadIndex >= 0) {
            return trimmed.substring(uploadIndex);
        }
        return trimmed;
    }

    private String toPublicUrl(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        if (trimmed.startsWith("/uploads/")) {
            return ServletUriComponentsBuilder.fromCurrentContextPath()
                    .path(trimmed)
                    .toUriString();
        }
        return trimmed;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("json_write_failed", error);
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
        userMapper.insertDebugUser(userId, "debug_" + userId, "Debug User", "", "", "miniapp");
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

    private static Long toEpochMilli(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toEpochMilli();
    }
}
