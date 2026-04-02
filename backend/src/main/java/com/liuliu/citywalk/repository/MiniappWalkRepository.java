package com.liuliu.citywalk.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.model.dto.request.MiniappCreateWalkRequest;
import com.liuliu.citywalk.model.dto.request.MiniappMissionReviewItem;
import com.liuliu.citywalk.model.dto.request.MiniappRoutePointRequest;
import com.liuliu.citywalk.model.dto.request.MiniappThemeSnapshotRequest;
import com.liuliu.citywalk.model.dto.response.MiniappMissionReviewResponse;
import com.liuliu.citywalk.model.dto.response.MiniappRoutePointResponse;
import com.liuliu.citywalk.model.dto.response.MiniappThemeSnapshotResponse;
import com.liuliu.citywalk.model.dto.response.MiniappWalkRecordResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Repository
public class MiniappWalkRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<MiniappWalkRecordResponse> rowMapper = (rs, rowNum) -> new MiniappWalkRecordResponse(
            String.valueOf(rs.getLong("id")),
            rs.getLong("user_id"),
            rs.getString("theme_title"),
            parseThemeSnapshot(rs.getString("theme_snapshot")),
            rs.getString("location_name"),
            rs.getString("location_context"),
            parseRoutePoints(rs.getString("route_points")),
            parseStringList(rs.getString("missions_completed")),
            parseMissionReviews(rs.getString("mission_reviews")),
            parseStringList(rs.getString("photo_list")),
            toPublicUrl(rs.getString("cover_image")),
            rs.getString("note_text"),
            rs.getBoolean("is_public"),
            rs.getString("walk_mode"),
            rs.getString("generation_source"),
            toEpochMilli(rs.getTimestamp("created_at"))
    );

    public MiniappWalkRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public MiniappWalkRecordResponse create(Long userId, MiniappCreateWalkRequest request) {
        ensureUserExists(userId);
        List<String> photoList = normalizeStoredPhotoList(request.photoList());
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
            ps.setString(2, request.themeSnapshot() != null ? safeText(request.themeSnapshot().title(), "城市漫步") : "城市漫步");
            ps.setString(3, writeJson(toThemeSnapshot(request.themeSnapshot())));
            ps.setString(4, safeText(request.locationName(), "当前位置"));
            ps.setString(5, safeText(request.locationContext(), "城市街道"));
            ps.setString(6, writeJson(toRoutePoints(request.routePoints())));
            ps.setString(7, writeJson(safeStringList(request.missionsCompleted())));
            ps.setString(8, writeJson(toMissionReviews(request.missionReviews())));
            ps.setString(9, writeJson(photoList));
            ps.setString(10, photoList.isEmpty() ? "" : photoList.get(0));
            ps.setString(11, safeText(request.noteText(), ""));
            ps.setBoolean(12, Boolean.TRUE.equals(request.isPublic()));
            ps.setString(13, safeText(request.walkMode(), "pure"));
            ps.setString(14, safeText(request.generationSource(), "backend"));
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("failed_to_create_walk");
        }
        return findById(String.valueOf(key.longValue())).orElseThrow(() -> new IllegalStateException("created_walk_not_found"));
    }

    public List<MiniappWalkRecordResponse> listMyWalks(Long userId, int limit) {
        return jdbcTemplate.query(
                """
                select id, user_id, theme_title, theme_snapshot, location_name, location_context,
                       route_points, missions_completed, mission_reviews, photo_list, cover_image,
                       note_text, is_public, walk_mode, generation_source, created_at
                from walk_records
                where user_id = ? and status = 'active'
                order by created_at desc
                limit ?
                """,
                rowMapper,
                userId,
                limit
        );
    }

    public List<MiniappWalkRecordResponse> listPublicWalks(int limit) {
        return jdbcTemplate.query(
                """
                select id, user_id, theme_title, theme_snapshot, location_name, location_context,
                       route_points, missions_completed, mission_reviews, photo_list, cover_image,
                       note_text, is_public, walk_mode, generation_source, created_at
                from walk_records
                where is_public = 1 and status = 'active'
                order by created_at desc
                limit ?
                """,
                rowMapper,
                limit
        );
    }

    public Optional<MiniappWalkRecordResponse> findById(String id) {
        List<MiniappWalkRecordResponse> results = jdbcTemplate.query(
                """
                select id, user_id, theme_title, theme_snapshot, location_name, location_context,
                       route_points, missions_completed, mission_reviews, photo_list, cover_image,
                       note_text, is_public, walk_mode, generation_source, created_at
                from walk_records
                where id = ? and status = 'active'
                limit 1
                """,
                rowMapper,
                Long.parseLong(id)
        );
        return results.stream().findFirst();
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
                ) values (?, ?, ?, ?, 'user', 'active', 'miniapp', now(), now(), now())
                """,
                userId,
                "debug_" + userId,
                "Debug User",
                ""
        );
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
