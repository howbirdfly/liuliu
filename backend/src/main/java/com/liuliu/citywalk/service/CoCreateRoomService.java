package com.liuliu.citywalk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.model.dto.request.CoCreateRoomThemeRequest;
import com.liuliu.citywalk.model.dto.request.CreateCoCreateRoomRequest;
import com.liuliu.citywalk.model.dto.request.JoinCoCreateRoomRequest;
import com.liuliu.citywalk.model.dto.request.PathPointRequest;
import com.liuliu.citywalk.model.dto.request.UpdateCoCreateRoomStateRequest;
import com.liuliu.citywalk.model.dto.request.UpdateCoCreateRoomThemeRequest;
import com.liuliu.citywalk.model.dto.response.CoCreateRoomMemberResponse;
import com.liuliu.citywalk.model.dto.response.CoCreateRoomResponse;
import com.liuliu.citywalk.model.dto.response.CoCreateRoomThemeResponse;
import com.liuliu.citywalk.repository.CoCreateRoomRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final CoCreateRoomRepository roomRepository;
    private final UserSessionService userSessionService;
    private final ObjectMapper objectMapper;
    private final Random random = new Random();

    public CoCreateRoomService(
            CoCreateRoomRepository roomRepository,
            UserSessionService userSessionService,
            ObjectMapper objectMapper
    ) {
        this.roomRepository = roomRepository;
        this.userSessionService = userSessionService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CoCreateRoomResponse createRoom(String authorizationHeader, CreateCoCreateRoomRequest request) {
        UserSessionService.StoredUser user = requireUser(authorizationHeader);
        String roomCode = normalizeRequestedRoomCode(request.roomCode());
        if (roomCode == null) {
            roomCode = generateUniqueRoomCode();
        } else if (roomRepository.findRoomByCode(roomCode).isPresent()) {
            throw new IllegalStateException("room_code_already_exists");
        }

        CoCreateRoomRepository.RoomRecord room = roomRepository.createRoom(roomCode, user.id(), writeJson(normalizeTheme(request.theme())));
        roomRepository.addMember(room.id(), user.id(), safeNickname(user.nickName()), safeAvatar(user.avatarUrl()), TRACK_COLORS.get(0));
        return getRoom(authorizationHeader, room.roomCode());
    }

    @Transactional
    public CoCreateRoomResponse joinRoom(String authorizationHeader, JoinCoCreateRoomRequest request) {
        UserSessionService.StoredUser user = requireUser(authorizationHeader);
        String roomCode = normalizeRoomCode(request.roomCode());
        CoCreateRoomRepository.RoomRecord room = roomRepository.findRoomByCode(roomCode)
                .orElseThrow(() -> new IllegalStateException("room_not_found"));

        Optional<CoCreateRoomRepository.MemberRecord> existingMember = roomRepository.findMember(room.id(), user.id());
        if (existingMember.isEmpty()) {
            int memberCount = roomRepository.countMembers(room.id());
            if (memberCount >= MEMBER_LIMIT) {
                throw new IllegalStateException("room_is_full");
            }
            String trackColor = TRACK_COLORS.get(Math.min(memberCount, TRACK_COLORS.size() - 1));
            roomRepository.addMember(room.id(), user.id(), safeNickname(user.nickName()), safeAvatar(user.avatarUrl()), trackColor);
        } else {
            roomRepository.addMember(room.id(), user.id(), safeNickname(user.nickName()), safeAvatar(user.avatarUrl()), existingMember.get().trackColor());
        }

        return getRoom(authorizationHeader, room.roomCode());
    }

    public CoCreateRoomResponse getRoom(String authorizationHeader, String roomCode) {
        UserSessionService.StoredUser user = requireUser(authorizationHeader);
        CoCreateRoomRepository.RoomRecord room = roomRepository.findRoomByCode(normalizeRoomCode(roomCode))
                .orElseThrow(() -> new IllegalStateException("room_not_found"));
        roomRepository.findMember(room.id(), user.id()).orElseThrow(() -> new IllegalStateException("room_membership_required"));
        return toResponse(room);
    }

    @Transactional
    public CoCreateRoomResponse updateRoomState(String authorizationHeader, String roomCode, UpdateCoCreateRoomStateRequest request) {
        UserSessionService.StoredUser user = requireUser(authorizationHeader);
        CoCreateRoomRepository.RoomRecord room = roomRepository.findRoomByCode(normalizeRoomCode(roomCode))
                .orElseThrow(() -> new IllegalStateException("room_not_found"));
        roomRepository.findMember(room.id(), user.id()).orElseThrow(() -> new IllegalStateException("room_membership_required"));

        List<PathPointRequest> path = normalizePath(request.path());
        PathPointRequest currentPosition = normalizePoint(request.currentPosition());
        List<String> completedMissions = normalizeCompletedMissions(request.completedMissions());
        boolean isTracking = Boolean.TRUE.equals(request.isTracking());

        roomRepository.updateMemberState(
                room.id(),
                user.id(),
                writeJson(path),
                currentPosition == null ? null : writeJson(currentPosition),
                writeJson(completedMissions),
                isTracking
        );

        return toResponse(roomRepository.findRoomById(room.id()).orElseThrow(() -> new IllegalStateException("room_not_found")));
    }

    @Transactional
    public CoCreateRoomResponse updateRoomTheme(String authorizationHeader, String roomCode, UpdateCoCreateRoomThemeRequest request) {
        UserSessionService.StoredUser user = requireUser(authorizationHeader);
        CoCreateRoomRepository.RoomRecord room = roomRepository.findRoomByCode(normalizeRoomCode(roomCode))
                .orElseThrow(() -> new IllegalStateException("room_not_found"));
        if (!room.ownerUserId().equals(user.id())) {
            throw new IllegalStateException("room_owner_required");
        }
        roomRepository.updateRoomTheme(room.id(), writeJson(normalizeTheme(request.theme())));
        return getRoom(authorizationHeader, room.roomCode());
    }

    @Transactional
    public void leaveRoom(String authorizationHeader, String roomCode) {
        UserSessionService.StoredUser user = requireUser(authorizationHeader);
        CoCreateRoomRepository.RoomRecord room = roomRepository.findRoomByCode(normalizeRoomCode(roomCode))
                .orElseThrow(() -> new IllegalStateException("room_not_found"));
        roomRepository.deleteMember(room.id(), user.id());
        int remainingMembers = roomRepository.countMembers(room.id());
        if (remainingMembers <= 0) {
            roomRepository.deleteRoom(room.id());
            return;
        }

        if (room.ownerUserId().equals(user.id())) {
            roomRepository.listMembers(room.id()).stream()
                    .findFirst()
                    .ifPresent(member -> roomRepository.changeOwner(room.id(), member.userId()));
        }
    }

    private CoCreateRoomResponse toResponse(CoCreateRoomRepository.RoomRecord room) {
        List<CoCreateRoomRepository.MemberRecord> members = roomRepository.listMembers(room.id());
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
            if (roomRepository.findRoomByCode(roomCode).isEmpty()) {
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
}
