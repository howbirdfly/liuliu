package com.liuliu.citywalk.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.model.dto.request.CompletedMissionRequest;
import com.liuliu.citywalk.model.dto.request.CreateWalkRequest;
import com.liuliu.citywalk.model.dto.request.PathPointRequest;
import com.liuliu.citywalk.model.dto.response.WalkResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class WalkRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public WalkRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public WalkResponse create(Long userId, CreateWalkRequest request) {
        ensureUserExists(userId);
        List<String> completedMissions = normalizeCompletedMissions(request.completedMissions());
        List<Map<String, Object>> routePoints = normalizeRoutePoints(request.path());
        List<String> photoList = normalizePhotoList(request.photoUrl());
        String coverImage = photoList.isEmpty() ? "" : photoList.get(0);

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    insert into walk_records (
                      user_id, theme_title, theme_snapshot, location_name, location_context,
                      route_points, missions_completed, mission_reviews, photo_list, cover_image,
                      note_text, is_public, walk_mode, generation_source, status, created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', now(), now())
                    """,
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, userId);
            ps.setString(2, safeText(request.themeTitle(), "城市漫步"));
            ps.setString(3, writeJson(buildThemeSnapshot(request, completedMissions)));
            ps.setString(4, safeText(request.locationName(), "当前位置"));
            ps.setString(5, "");
            ps.setString(6, writeJson(routePoints));
            ps.setString(7, writeJson(completedMissions));
            ps.setString(8, writeJson(Map.of()));
            ps.setString(9, writeJson(photoList));
            ps.setString(10, coverImage);
            ps.setString(11, safeText(request.noteText(), ""));
            ps.setBoolean(12, Boolean.TRUE.equals(request.isPublic()));
            ps.setString(13, "web");
            ps.setString(14, "web-debug");
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("failed_to_create_walk");
        }
        return findById(String.valueOf(key.longValue())).orElseThrow(() -> new IllegalStateException("created_walk_not_found"));
    }

    public List<WalkResponse> listMyWalks(Long userId, int limit) {
        return jdbcTemplate.query(
                """
                select id, theme_title, theme_snapshot, location_name,
                       route_points, missions_completed, photo_list, cover_image,
                       note_text, is_public, created_at
                from walk_records
                where user_id = ? and status = 'active'
                order by created_at desc
                limit ?
                """,
                (rs, rowNum) -> toWalkResponse(
                        rs.getLong("id"),
                        rs.getString("theme_title"),
                        rs.getString("theme_snapshot"),
                        rs.getString("location_name"),
                        rs.getString("route_points"),
                        rs.getString("missions_completed"),
                        rs.getString("photo_list"),
                        rs.getString("cover_image"),
                        rs.getString("note_text"),
                        rs.getBoolean("is_public"),
                        rs.getTimestamp("created_at")
                ),
                userId,
                limit
        );
    }

    public List<WalkResponse> listPublicWalks(int limit) {
        return jdbcTemplate.query(
                """
                select id, theme_title, theme_snapshot, location_name,
                       route_points, missions_completed, photo_list, cover_image,
                       note_text, is_public, created_at
                from walk_records
                where is_public = 1 and status = 'active'
                order by created_at desc
                limit ?
                """,
                (rs, rowNum) -> toWalkResponse(
                        rs.getLong("id"),
                        rs.getString("theme_title"),
                        rs.getString("theme_snapshot"),
                        rs.getString("location_name"),
                        rs.getString("route_points"),
                        rs.getString("missions_completed"),
                        rs.getString("photo_list"),
                        rs.getString("cover_image"),
                        rs.getString("note_text"),
                        rs.getBoolean("is_public"),
                        rs.getTimestamp("created_at")
                ),
                limit
        );
    }

    public Optional<WalkResponse> findById(String id) {
        List<WalkResponse> results = jdbcTemplate.query(
                """
                select id, theme_title, theme_snapshot, location_name,
                       route_points, missions_completed, photo_list, cover_image,
                       note_text, is_public, created_at
                from walk_records
                where id = ? and status = 'active'
                limit 1
                """,
                (rs, rowNum) -> toWalkResponse(
                        rs.getLong("id"),
                        rs.getString("theme_title"),
                        rs.getString("theme_snapshot"),
                        rs.getString("location_name"),
                        rs.getString("route_points"),
                        rs.getString("missions_completed"),
                        rs.getString("photo_list"),
                        rs.getString("cover_image"),
                        rs.getString("note_text"),
                        rs.getBoolean("is_public"),
                        rs.getTimestamp("created_at")
                ),
                Long.parseLong(id)
        );
        return results.stream().findFirst();
    }

    private WalkResponse toWalkResponse(Long id,
                                        String themeTitle,
                                        String themeSnapshotJson,
                                        String locationName,
                                        String routePointsJson,
                                        String missionsCompletedJson,
                                        String photoListJson,
                                        String coverImage,
                                        String noteText,
                                        boolean isPublic,
                                        Timestamp createdAt) {
        Map<String, Object> snapshot = parseJson(themeSnapshotJson, new TypeReference<Map<String, Object>>() { }, Map.of());
        String themeCategory = snapshot.get("category") instanceof String value ? value : null;
        List<Map<String, Object>> routePoints = parseJson(routePointsJson, new TypeReference<List<Map<String, Object>>>() { }, List.of());
        List<String> completedMissions = parseJson(missionsCompletedJson, new TypeReference<List<String>>() { }, List.of());
        List<String> photoList = parseJson(photoListJson, new TypeReference<List<String>>() { }, List.of());
        String photoUrl = !photoList.isEmpty() ? photoList.get(0) : coverImage;
        String recordUnit = photoUrl != null && !photoUrl.isBlank()
                ? "image"
                : !routePoints.isEmpty()
                    ? "location"
                    : "event";
        return new WalkResponse(
                id,
                themeTitle,
                themeCategory,
                locationName,
                recordUnit,
                isPublic,
                noteText,
                photoUrl,
                null,
                null,
                routePoints,
                completedMissions,
                toEpochMilli(createdAt)
        );
    }

    private Long toEpochMilli(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toEpochMilli();
    }

    private Map<String, Object> buildThemeSnapshot(CreateWalkRequest request, List<String> missions) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("title", safeText(request.themeTitle(), "城市漫步"));
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
        Integer count = jdbcTemplate.queryForObject(
                "select count(1) from users where id = ?",
                Integer.class,
                userId
        );
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update(
                """
                insert into users (
                  id, openid, nickname, avatar_url, role, status, source, created_at, updated_at, last_login_at
                ) values (?, ?, ?, ?, 'user', 'active', 'web', now(), now(), now())
                """,
                userId,
                "web_debug_" + userId,
                "Web Debug User",
                ""
        );
    }
}
