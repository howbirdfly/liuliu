package com.liuliu.citywalk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.mapper.UserMapper;
import com.liuliu.citywalk.mapper.WalkRecordMapper;
import com.liuliu.citywalk.mapper.entity.UserEntity;
import com.liuliu.citywalk.mapper.entity.WalkRecordEntity;
import com.liuliu.citywalk.model.dto.request.CompletedMissionRequest;
import com.liuliu.citywalk.model.dto.request.CreateWalkRequest;
import com.liuliu.citywalk.model.dto.request.PathPointRequest;
import com.liuliu.citywalk.model.dto.request.RoomMemberTrackRequest;
import com.liuliu.citywalk.model.dto.request.UpdateWalkRequest;
import com.liuliu.citywalk.model.dto.response.OperationResultResponse;
import com.liuliu.citywalk.model.dto.response.RoomMemberTrackResponse;
import com.liuliu.citywalk.model.dto.response.WalkResponse;
import com.liuliu.citywalk.model.dto.response.WalkThemeSnapshotResponse;
import com.liuliu.citywalk.service.rag.CommunityKnowledgeIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class WalkService {

    private static final Logger log = LoggerFactory.getLogger(WalkService.class);

    private final WalkRecordMapper walkRecordMapper;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;
    private final CommunityKnowledgeIngestionService communityKnowledgeIngestionService;

    public WalkService(
            WalkRecordMapper walkRecordMapper,
            UserMapper userMapper,
            ObjectMapper objectMapper,
            CommunityKnowledgeIngestionService communityKnowledgeIngestionService
    ) {
        this.walkRecordMapper = walkRecordMapper;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
        this.communityKnowledgeIngestionService = communityKnowledgeIngestionService;
    }

    public WalkResponse create(Long userId, CreateWalkRequest request) {
        ensureUserExists(userId);
        List<String> completedMissions = normalizeCompletedMissions(request.completedMissions());
        List<String> tags = normalizeTags(request.tags());
        List<Map<String, Object>> routePoints = normalizeRoutePoints(request.path());
        List<String> photoList = normalizePhotoList(request.photoUrl());
        String coverImage = photoList.isEmpty() ? "" : photoList.get(0);

        WalkRecordEntity entity = new WalkRecordEntity();
        entity.setUserId(userId);
        entity.setThemeTitle(safeText(request.themeTitle(), "城市漫步"));
        entity.setThemeSnapshot(writeJson(buildThemeSnapshot(request, completedMissions)));
        entity.setLocationName(safeText(request.locationName(), "当前位置"));
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
        if (!tags.isEmpty()) {
            walkRecordMapper.insertTags(entity.getId(), tags);
        }
        WalkResponse response = getDetail(entity.getId());
        if (Boolean.TRUE.equals(entity.getIsPublic())) {
            syncWalkKnowledgeSafely(entity.getId());
        }
        return response;
    }

    public List<WalkResponse> listMyWalks(Long userId, int limit) {
        return walkRecordMapper.findMyActive(userId, limit).stream()
                .map(this::toWalkResponse)
                .toList();
    }

    public WalkResponse getLatestMyWalk(Long userId) {
        return Optional.ofNullable(walkRecordMapper.findLatestMyActive(userId))
                .map(this::toWalkResponse)
                .orElse(null);
    }

    public List<WalkResponse> listPublicWalks(int limit) {
        return walkRecordMapper.findPublicActive(limit).stream()
                .map(this::toWalkResponse)
                .toList();
    }

    public WalkResponse getDetail(Long id) {
        return Optional.ofNullable(walkRecordMapper.findActiveById(id)).map(this::toWalkResponse).orElse(null);
    }

    @Transactional
    public OperationResultResponse deleteWalk(Long walkId, Long userId) {
        WalkRecordEntity walk = walkRecordMapper.findActiveById(walkId);
        if (walk == null) {
            throw new IllegalStateException("walk_not_found");
        }
        if (!equalsLong(walk.getUserId(), userId)) {
            throw new IllegalStateException("walk_forbidden");
        }

        walkRecordMapper.softDeleteById(walkId);
        if (Boolean.TRUE.equals(walk.getIsPublic())) {
            removeWalkKnowledgeSafely(walkId);
        }
        return new OperationResultResponse(Boolean.TRUE);
    }

    @Transactional
    public WalkResponse updateWalk(Long walkId, Long userId, UpdateWalkRequest request) {
        WalkRecordEntity walk = walkRecordMapper.findActiveById(walkId);
        if (walk == null) {
            throw new IllegalStateException("walk_not_found");
        }
        if (!equalsLong(walk.getUserId(), userId)) {
            throw new IllegalStateException("walk_forbidden");
        }

        String nextTitle = safeText(request.themeTitle(), walk.getThemeTitle());
        String nextNote = request.noteText() == null ? "" : request.noteText().trim();
        Boolean nextPublic = Boolean.TRUE.equals(request.isPublic());
        List<String> nextTags = normalizeTags(request.tags());

        Map<String, Object> snapshot = new LinkedHashMap<>(
                parseJson(walk.getThemeSnapshot(), new TypeReference<Map<String, Object>>() { }, Map.of())
        );
        snapshot.put("title", nextTitle);
        snapshot.put("description", nextNote);
        snapshot.put("category", safeText(request.themeCategory(), ""));
        snapshot.put("tags", nextTags);

        walkRecordMapper.updateEditableFields(
                walkId,
                nextTitle,
                writeJson(snapshot),
                nextNote,
                nextPublic
        );
        walkRecordMapper.deleteTagsByWalkId(walkId);
        if (!nextTags.isEmpty()) {
            walkRecordMapper.insertTags(walkId, nextTags);
        }

        WalkResponse response = getDetail(walkId);
        if (nextPublic) {
            syncWalkKnowledgeSafely(walkId);
        } else if (Boolean.TRUE.equals(walk.getIsPublic())) {
            removeWalkKnowledgeSafely(walkId);
        }
        return response;
    }

    private void syncWalkKnowledgeSafely(Long walkId) {
        try {
            communityKnowledgeIngestionService.syncPublicWalkById(walkId);
        } catch (Exception error) {
            log.warn("Sync public walk knowledge failed, walkId={}", walkId, error);
        }
    }

    private void removeWalkKnowledgeSafely(Long walkId) {
        try {
            communityKnowledgeIngestionService.removeWalkById(walkId);
        } catch (Exception error) {
            log.warn("Remove public walk knowledge failed, walkId={}", walkId, error);
        }
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
        UserEntity author = resolveAuthor(entity.getUserId());
        return new WalkResponse(
                entity.getId(),
                entity.getThemeTitle(),
                themeCategory,
                buildThemeSnapshotResponse(snapshot, completedMissions, photoUrl),
                entity.getLocationName(),
                entity.getUserId(),
                resolveAuthorNickname(author),
                resolveAuthorAvatar(author),
                recordUnit,
                entity.getIsPublic(),
                entity.getNoteText(),
                photoUrl,
                null,
                null,
                routePoints,
                completedMissions,
                snapshot.get("roomCode") instanceof String roomCode ? roomCode : null,
                parseRoomMembers(snapshot.get("roomMembers")),
                walkRecordMapper.listTagsByWalkId(entity.getId()),
                toEpochMilli(entity.getCreatedAt())
        );
    }

    private WalkThemeSnapshotResponse buildThemeSnapshotResponse(Map<String, Object> snapshot,
                                                                List<String> completedMissions,
                                                                String photoUrl) {
        List<String> missions = snapshot.get("missions") instanceof List<?> items
                ? items.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                : completedMissions;
        return new WalkThemeSnapshotResponse(
                snapshot.get("title") instanceof String title ? safeText(title, "") : "",
                snapshot.get("description") instanceof String description ? safeText(description, "") : "",
                snapshot.get("category") instanceof String category ? safeText(category, "") : "",
                missions,
                snapshot.get("vibeColor") instanceof String vibeColor ? safeText(vibeColor, "#5a5a40") : "#5a5a40",
                snapshot.get("provider") instanceof String provider ? safeText(provider, "") : "",
                snapshot.get("coverImageUrl") instanceof String coverImageUrl
                        ? safeText(coverImageUrl, photoUrl == null ? "" : photoUrl)
                        : (photoUrl == null ? "" : photoUrl)
        );
    }

    private UserEntity resolveAuthor(Long userId) {
        if (userId == null || userId <= 0) {
            return null;
        }
        return userMapper.findById(userId);
    }

    private String resolveAuthorNickname(UserEntity author) {
        if (author == null || author.getNickname() == null || author.getNickname().isBlank()) {
            return "社区漫步者";
        }
        return author.getNickname().trim();
    }

    private String resolveAuthorAvatar(UserEntity author) {
        if (author == null || author.getAvatarUrl() == null) {
            return "";
        }
        return author.getAvatarUrl().trim();
    }

    private Map<String, Object> buildThemeSnapshot(CreateWalkRequest request, List<String> missions) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("title", safeText(request.themeTitle(), "城市漫步"));
        snapshot.put("description", safeText(request.noteText(), ""));
        snapshot.put("category", safeText(request.themeCategory(), ""));
        snapshot.put("missions", missions);
        snapshot.put("tags", normalizeTags(request.tags()));
        snapshot.put("vibeColor", "#5a5a40");
        snapshot.put("provider", "web-debug");
        if (request.roomCode() != null && !request.roomCode().isBlank()) {
            snapshot.put("roomCode", request.roomCode().trim());
        }
        List<RoomMemberTrackResponse> roomMembers = normalizeRoomMembers(request.roomMembers());
        if (!roomMembers.isEmpty()) {
            snapshot.put("roomMembers", roomMembers);
        }
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

    private List<RoomMemberTrackResponse> normalizeRoomMembers(List<RoomMemberTrackRequest> roomMembers) {
        if (roomMembers == null || roomMembers.isEmpty()) {
            return List.of();
        }
        return roomMembers.stream()
                .filter(member -> member != null && member.userId() != null)
                .map(member -> new RoomMemberTrackResponse(
                        member.userId(),
                        safeText(member.nickname(), "队友"),
                        safeText(member.trackColor(), "#2563eb"),
                        Boolean.TRUE.equals(member.isOwner()),
                        Boolean.TRUE.equals(member.isTracking()),
                        normalizePoint(member.currentPosition()),
                        normalizePointList(member.path()),
                        normalizeCompletedMissionStrings(member.completedMissions())
                ))
                .toList();
    }

    private PathPointRequest normalizePoint(PathPointRequest point) {
        if (point == null || point.lat() == null || point.lng() == null || point.timestamp() == null) {
            return null;
        }
        return new PathPointRequest(point.lat(), point.lng(), point.timestamp());
    }

    private List<PathPointRequest> normalizePointList(List<PathPointRequest> points) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        return points.stream()
                .map(this::normalizePoint)
                .filter(point -> point != null)
                .toList();
    }

    private List<String> normalizeCompletedMissionStrings(List<String> completedMissions) {
        if (completedMissions == null || completedMissions.isEmpty()) {
            return List.of();
        }
        return completedMissions.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return tags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(tag -> tag.trim().replaceFirst("^#+", ""))
                .filter(tag -> !tag.isBlank())
                .map(tag -> tag.length() > 20 ? tag.substring(0, 20) : tag)
                .distinct()
                .limit(8)
                .toList();
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

    private List<RoomMemberTrackResponse> parseRoomMembers(Object rawRoomMembers) {
        if (rawRoomMembers == null) {
            return List.of();
        }
        try {
            return objectMapper.convertValue(rawRoomMembers, new TypeReference<List<RoomMemberTrackResponse>>() { });
        } catch (IllegalArgumentException error) {
            return List.of();
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
        userMapper.insertDebugUser(userId, "web_debug_" + userId, "Web Debug User", "", "", "web");
    }

    private Long toEpochMilli(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toEpochMilli();
    }

    private boolean equalsLong(Long left, Long right) {
        return left != null && left.equals(right);
    }
}
