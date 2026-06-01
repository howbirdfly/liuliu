package com.liuliu.citywalk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.config.CoCreateRoomProperties;
import com.liuliu.citywalk.mapper.CoCreateRoomMapper;
import com.liuliu.citywalk.mapper.entity.CoCreateRoomEntity;
import com.liuliu.citywalk.mapper.entity.CoCreateRoomMemberEntity;
import com.liuliu.citywalk.model.dto.request.CoCreateRoomThemeRequest;
import com.liuliu.citywalk.model.dto.request.CreateCoCreateRoomRequest;
import com.liuliu.citywalk.model.dto.request.JoinCoCreateRoomRequest;
import com.liuliu.citywalk.model.dto.request.PathPointRequest;
import com.liuliu.citywalk.model.dto.request.UpdateCoCreateRoomStateRequest;
import com.liuliu.citywalk.model.dto.request.UpdateCoCreateRoomThemeRequest;
import com.liuliu.citywalk.model.dto.response.CoCreateRoomMemberResponse;
import com.liuliu.citywalk.model.dto.response.CoCreateRoomResponse;
import com.liuliu.citywalk.model.dto.response.CoCreateRoomThemeResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

@Service
public class CoCreateRoomService {

    private static final int MEMBER_LIMIT = 5;
    private static final List<String> TRACK_COLORS = List.of(
            "#2563eb",
            "#f97316",
            "#10b981",
            "#8b5cf6",
            "#ec4899"
    );

    private final CoCreateRoomMapper roomMapper;
    private final UserSessionService userSessionService;
    private final ObjectMapper objectMapper;
    private final CoCreateRoomRealtimeService coCreateRoomRealtimeService;
    private final CoCreateRoomProperties coCreateRoomProperties;
    private final Random random = new Random();

    public CoCreateRoomService(
            CoCreateRoomMapper roomMapper,
            UserSessionService userSessionService,
            ObjectMapper objectMapper,
            CoCreateRoomRealtimeService coCreateRoomRealtimeService,
            CoCreateRoomProperties coCreateRoomProperties
    ) {
        this.roomMapper = roomMapper;
        this.userSessionService = userSessionService;
        this.objectMapper = objectMapper;
        this.coCreateRoomRealtimeService = coCreateRoomRealtimeService;
        this.coCreateRoomProperties = coCreateRoomProperties;
    }

    @Transactional
    public CoCreateRoomResponse createRoom(String authorizationHeader, CreateCoCreateRoomRequest request) {
        UserSessionService.StoredUser user = requireUser(authorizationHeader);
        String roomCode = normalizeRequestedRoomCode(request.roomCode());
        if (roomCode == null) {
            roomCode = generateUniqueRoomCode();
        } else if (findRoomByCode(roomCode).isPresent()) {
            throw new IllegalStateException("room_code_already_exists");
        }

        RoomRecord room = createRoomRecord(roomCode, user.id(), writeJson(normalizeTheme(request.theme())));
        addMember(room.id(), user.id(), safeNickname(user.nickName()), safeAvatar(user.avatarUrl()), TRACK_COLORS.get(0));
        CoCreateRoomResponse response = getRoom(authorizationHeader, room.roomCode());
        coCreateRoomRealtimeService.broadcastRoomSnapshot(response.roomCode(), response);
        return response;
    }

    @Transactional
    public CoCreateRoomResponse joinRoom(String authorizationHeader, JoinCoCreateRoomRequest request) {
        UserSessionService.StoredUser user = requireUser(authorizationHeader);
        String roomCode = normalizeRoomCode(request.roomCode());
        RoomRecord room = findRoomByCode(roomCode)
                .orElseThrow(() -> new IllegalStateException("room_not_found"));

        Optional<MemberRecord> existingMember = findMember(room.id(), user.id());
        if (existingMember.isEmpty()) {
            int memberCount = countMembers(room.id());
            if (memberCount >= MEMBER_LIMIT) {
                throw new IllegalStateException("room_is_full");
            }
            String trackColor = TRACK_COLORS.get(Math.min(memberCount, TRACK_COLORS.size() - 1));
            addMember(room.id(), user.id(), safeNickname(user.nickName()), safeAvatar(user.avatarUrl()), trackColor);
        } else {
            addMember(room.id(), user.id(), safeNickname(user.nickName()), safeAvatar(user.avatarUrl()), existingMember.get().trackColor());
        }

        CoCreateRoomResponse response = getRoom(authorizationHeader, room.roomCode());
        coCreateRoomRealtimeService.broadcastRoomSnapshot(response.roomCode(), response);
        return response;
    }

    public CoCreateRoomResponse getRoom(String authorizationHeader, String roomCode) {
        UserSessionService.StoredUser user = requireUser(authorizationHeader);
        RoomRecord room = findRoomByCode(normalizeRoomCode(roomCode))
                .orElseThrow(() -> new IllegalStateException("room_not_found"));
        findMember(room.id(), user.id()).orElseThrow(() -> new IllegalStateException("room_membership_required"));
        return toResponse(room);
    }

    public CoCreateRoomResponse getCurrentRoom(String authorizationHeader) {
        UserSessionService.StoredUser user = requireUser(authorizationHeader);
        RoomRecord room = findLatestRoomByMemberUserId(user.id())
                .orElseThrow(() -> new IllegalStateException("room_not_found"));
        findMember(room.id(), user.id()).orElseThrow(() -> new IllegalStateException("room_membership_required"));
        return toResponse(room);
    }

    @Transactional
    public CoCreateRoomResponse updateRoomState(String authorizationHeader, String roomCode, UpdateCoCreateRoomStateRequest request) {
        UserSessionService.StoredUser user = requireUser(authorizationHeader);
        RoomRecord room = findRoomByCode(normalizeRoomCode(roomCode))
                .orElseThrow(() -> new IllegalStateException("room_not_found"));
        findMember(room.id(), user.id()).orElseThrow(() -> new IllegalStateException("room_membership_required"));

        List<PathPointRequest> path = normalizePath(request.path());
        PathPointRequest currentPosition = normalizePoint(request.currentPosition());
        List<String> completedMissions = normalizeCompletedMissions(request.completedMissions());
        boolean isTracking = Boolean.TRUE.equals(request.isTracking());

        updateMemberState(
                room.id(),
                user.id(),
                writeJson(path),
                currentPosition == null ? null : writeJson(currentPosition),
                writeJson(completedMissions),
                isTracking
        );

        CoCreateRoomResponse response = toResponse(findRoomById(room.id()).orElseThrow(() -> new IllegalStateException("room_not_found")));
        coCreateRoomRealtimeService.broadcastRoomSnapshot(response.roomCode(), response);
        return response;
    }

    @Transactional
    public CoCreateRoomResponse updateRoomTheme(String authorizationHeader, String roomCode, UpdateCoCreateRoomThemeRequest request) {
        UserSessionService.StoredUser user = requireUser(authorizationHeader);
        RoomRecord room = findRoomByCode(normalizeRoomCode(roomCode))
                .orElseThrow(() -> new IllegalStateException("room_not_found"));
        if (!room.ownerUserId().equals(user.id())) {
            throw new IllegalStateException("room_owner_required");
        }
        roomMapper.updateRoomTheme(room.id(), writeJson(normalizeTheme(request.theme())));
        CoCreateRoomResponse response = getRoom(authorizationHeader, room.roomCode());
        coCreateRoomRealtimeService.broadcastRoomSnapshot(response.roomCode(), response);
        return response;
    }

    @Transactional
    public void leaveRoom(String authorizationHeader, String roomCode) {
        UserSessionService.StoredUser user = requireUser(authorizationHeader);
        RoomRecord room = findRoomByCode(normalizeRoomCode(roomCode))
                .orElseThrow(() -> new IllegalStateException("room_not_found"));
        roomMapper.deleteMember(room.id(), user.id());
        int remainingMembers = countMembers(room.id());
        if (remainingMembers <= 0) {
            deleteRoom(room.id());
            coCreateRoomRealtimeService.broadcastRoomClosed(room.roomCode());
            return;
        }

        if (room.ownerUserId().equals(user.id())) {
            listMembers(room.id()).stream()
                    .findFirst()
                    .ifPresent(member -> roomMapper.changeOwner(room.id(), member.userId()));
        }

        findRoomById(room.id())
                .map(this::toResponse)
                .ifPresent(response -> coCreateRoomRealtimeService.broadcastRoomSnapshot(response.roomCode(), response));
    }

    private CoCreateRoomResponse toResponse(RoomRecord room) {
        List<MemberRecord> members = listMembers(room.id());
        return new CoCreateRoomResponse(
                room.roomCode(),
                room.ownerUserId(),
                MEMBER_LIMIT,
                parseTheme(room.themeSnapshotJson()),
                members.stream().map(member -> new CoCreateRoomMemberResponse(
                        member.userId(),
                        member.nickname(),
                        member.avatarUrl(),
                        member.trackColor(),
                        room.ownerUserId().equals(member.userId()),
                        Boolean.TRUE.equals(member.isTracking()),
                        parsePoint(member.currentPositionJson()),
                        parsePath(member.routePointsJson()),
                        parseCompletedMissions(member.completedMissionsJson()),
                        member.lastActiveAt()
                )).toList(),
                room.createdAt()
        );
    }

    private RoomRecord createRoomRecord(String roomCode, Long ownerUserId, String themeSnapshotJson) {
        CoCreateRoomEntity entity = new CoCreateRoomEntity();
        entity.setRoomCode(roomCode);
        entity.setOwnerUserId(ownerUserId);
        entity.setThemeSnapshot(themeSnapshotJson);
        entity.setStatus("active");
        roomMapper.insertRoom(entity);
        if (entity.getId() == null) {
            throw new IllegalStateException("failed_to_create_room");
        }
        return loadRoomById(entity.getId()).orElseThrow(() -> new IllegalStateException("created_room_not_found"));
    }

    private Optional<RoomRecord> findRoomByCode(String roomCode) {
        CoCreateRoomEntity entity = roomMapper.findActiveRoomByCode(roomCode);
        if (entity == null) {
            return Optional.empty();
        }
        cleanupInactiveMembers(entity.getId());
        return loadRoomByCode(roomCode);
    }

    private Optional<RoomRecord> findRoomById(Long roomId) {
        CoCreateRoomEntity entity = roomMapper.findActiveRoomById(roomId);
        if (entity == null) {
            return Optional.empty();
        }
        cleanupInactiveMembers(roomId);
        return loadRoomById(roomId);
    }

    private Optional<RoomRecord> findLatestRoomByMemberUserId(Long userId) {
        CoCreateRoomEntity entity = roomMapper.findLatestActiveRoomByMemberUserId(userId);
        if (entity == null) {
            return Optional.empty();
        }
        cleanupInactiveMembers(entity.getId());
        return loadRoomById(entity.getId());
    }

    private Optional<RoomRecord> loadRoomByCode(String roomCode) {
        return Optional.ofNullable(roomMapper.findActiveRoomByCode(roomCode)).map(this::toRoomRecord);
    }

    private Optional<RoomRecord> loadRoomById(Long roomId) {
        return Optional.ofNullable(roomMapper.findActiveRoomById(roomId)).map(this::toRoomRecord);
    }

    private int countMembers(Long roomId) {
        Integer count = roomMapper.countMembers(roomId);
        return count == null ? 0 : count;
    }

    private Optional<MemberRecord> findMember(Long roomId, Long userId) {
        return Optional.ofNullable(roomMapper.findMember(roomId, userId)).map(this::toMemberRecord);
    }

    private List<MemberRecord> listMembers(Long roomId) {
        return roomMapper.listMembers(roomId).stream()
                .map(this::toMemberRecord)
                .toList();
    }

    private void addMember(Long roomId, Long userId, String nickname, String avatarUrl, String trackColor) {
        CoCreateRoomMemberEntity entity = new CoCreateRoomMemberEntity();
        entity.setRoomId(roomId);
        entity.setUserId(userId);
        entity.setNickname(nickname);
        entity.setAvatarUrl(avatarUrl);
        entity.setTrackColor(trackColor);
        entity.setRoutePoints("[]");
        entity.setCurrentPosition(null);
        entity.setCompletedMissions("[]");
        entity.setIsTracking(false);
        roomMapper.upsertMember(entity);
    }

    private void updateMemberState(Long roomId,
                                   Long userId,
                                   String routePointsJson,
                                   String currentPositionJson,
                                   String completedMissionsJson,
                                   boolean isTracking) {
        roomMapper.updateMemberState(roomId, userId, routePointsJson, currentPositionJson, completedMissionsJson, isTracking);
    }

    private void deleteRoom(Long roomId) {
        roomMapper.deleteMembersByRoomId(roomId);
        roomMapper.deleteRoomById(roomId);
    }

    private void cleanupInactiveMembers(Long roomId) {
        long timeoutMillis = Math.max(60_000L, coCreateRoomProperties.getMemberInactiveTimeoutMillis());
        Timestamp cutoff = Timestamp.from(Instant.now().minusMillis(timeoutMillis));
        roomMapper.deleteInactiveMembers(roomId, cutoff);

        int remainingMembers = countMembers(roomId);
        if (remainingMembers <= 0) {
            deleteRoom(roomId);
            return;
        }

        RoomRecord room = Optional.ofNullable(roomMapper.findActiveRoomById(roomId))
                .map(this::toRoomRecord)
                .orElse(null);
        if (room == null) {
            return;
        }

        boolean ownerStillPresent = findMember(roomId, room.ownerUserId()).isPresent();
        if (!ownerStillPresent) {
            listMembers(roomId).stream()
                    .findFirst()
                    .ifPresent(member -> roomMapper.changeOwner(roomId, member.userId()));
        }
    }

    private RoomRecord toRoomRecord(CoCreateRoomEntity entity) {
        return new RoomRecord(
                entity.getId(),
                entity.getRoomCode(),
                entity.getOwnerUserId(),
                entity.getThemeSnapshot(),
                entity.getStatus(),
                toEpochMilli(entity.getCreatedAt()),
                toEpochMilli(entity.getUpdatedAt())
        );
    }

    private MemberRecord toMemberRecord(CoCreateRoomMemberEntity entity) {
        return new MemberRecord(
                entity.getId(),
                entity.getRoomId(),
                entity.getUserId(),
                entity.getNickname(),
                entity.getAvatarUrl(),
                entity.getTrackColor(),
                entity.getRoutePoints(),
                entity.getCurrentPosition(),
                entity.getCompletedMissions(),
                entity.getIsTracking(),
                toEpochMilli(entity.getLastActiveAt()),
                toEpochMilli(entity.getCreatedAt())
        );
    }

    private UserSessionService.StoredUser requireUser(String authorizationHeader) {
        UserSessionService.StoredUser user = userSessionService.resolveUser(authorizationHeader);
        if (user == null || user.isGuest()) {
            throw new IllegalStateException("login_required");
        }
        return user;
    }

    private String generateUniqueRoomCode() {
        for (int attempt = 0; attempt < 20; attempt++) {
            String roomCode = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
            if (findRoomByCode(roomCode).isEmpty()) {
                return roomCode;
            }
        }
        throw new IllegalStateException("failed_to_generate_room_code");
    }

    private String normalizeRequestedRoomCode(String roomCode) {
        if (roomCode == null || roomCode.isBlank()) {
            return null;
        }
        String normalized = normalizeRoomCode(roomCode);
        if (normalized.length() < 4 || normalized.length() > 12) {
            throw new IllegalStateException("room_code_invalid");
        }
        return normalized;
    }

    private String normalizeRoomCode(String roomCode) {
        if (roomCode == null || roomCode.isBlank()) {
            throw new IllegalStateException("room_code_required");
        }
        return roomCode.trim().toUpperCase(Locale.ROOT);
    }

    private String safeNickname(String nickname) {
        return nickname == null || nickname.isBlank() ? "漫步同伴" : nickname.trim();
    }

    private String safeAvatar(String avatarUrl) {
        return avatarUrl == null ? "" : avatarUrl.trim();
    }

    private CoCreateRoomThemeResponse normalizeTheme(CoCreateRoomThemeRequest theme) {
        List<String> missions = theme == null || theme.missions() == null ? List.of() : theme.missions().stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .toList();
        return new CoCreateRoomThemeResponse(
                theme == null ? "" : safeString(theme.title()),
                theme == null ? "" : safeString(theme.description()),
                theme == null ? "" : safeString(theme.category()),
                missions,
                theme == null ? "#334155" : safeString(theme.vibeColor(), "#334155"),
                theme == null ? "" : safeString(theme.provider()),
                theme == null ? "" : safeString(theme.coverImageUrl())
        );
    }

    private List<PathPointRequest> normalizePath(List<PathPointRequest> path) {
        if (path == null || path.isEmpty()) {
            return List.of();
        }
        List<PathPointRequest> normalized = new ArrayList<>(path.size());
        for (PathPointRequest point : path) {
            PathPointRequest normalizedPoint = normalizePoint(point);
            if (normalizedPoint != null) {
                normalized.add(normalizedPoint);
            }
        }
        return normalized;
    }

    private PathPointRequest normalizePoint(PathPointRequest point) {
        if (point == null || point.lat() == null || point.lng() == null || point.timestamp() == null) {
            return null;
        }
        return new PathPointRequest(point.lat(), point.lng(), point.timestamp());
    }

    private List<String> normalizeCompletedMissions(List<String> completedMissions) {
        if (completedMissions == null || completedMissions.isEmpty()) {
            return List.of();
        }
        return completedMissions.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private String safeString(String value) {
        return safeString(value, "");
    }

    private String safeString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("json_write_failed", error);
        }
    }

    private CoCreateRoomThemeResponse parseTheme(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, CoCreateRoomThemeResponse.class);
        } catch (Exception error) {
            try {
                Map<String, Object> map = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
                List<String> missions = map.get("missions") instanceof List<?> items
                        ? items.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                        : List.of();
                return new CoCreateRoomThemeResponse(
                        safeString((String) map.get("title")),
                        safeString((String) map.get("description")),
                        safeString((String) map.get("category")),
                        missions,
                        safeString((String) map.get("vibeColor"), "#334155"),
                        safeString((String) map.get("provider")),
                        safeString((String) map.get("coverImageUrl"))
                );
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private List<PathPointRequest> parsePath(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<PathPointRequest>>() {});
        } catch (Exception error) {
            return List.of();
        }
    }

    private PathPointRequest parsePoint(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, PathPointRequest.class);
        } catch (Exception error) {
            return null;
        }
    }

    private List<String> parseCompletedMissions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception error) {
            return List.of();
        }
    }

    private static Long toEpochMilli(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toEpochMilli();
    }

    private record RoomRecord(
            Long id,
            String roomCode,
            Long ownerUserId,
            String themeSnapshotJson,
            String status,
            Long createdAt,
            Long updatedAt
    ) {
    }

    private record MemberRecord(
            Long id,
            Long roomId,
            Long userId,
            String nickname,
            String avatarUrl,
            String trackColor,
            String routePointsJson,
            String currentPositionJson,
            String completedMissionsJson,
            Boolean isTracking,
            Long lastActiveAt,
            Long createdAt
    ) {
    }
}
