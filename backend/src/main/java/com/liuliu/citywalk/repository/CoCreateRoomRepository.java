package com.liuliu.citywalk.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class CoCreateRoomRepository {

    private static final RowMapper<RoomRecord> ROOM_ROW_MAPPER = (rs, rowNum) -> new RoomRecord(
            rs.getLong("id"),
            rs.getString("room_code"),
            rs.getLong("owner_user_id"),
            rs.getString("theme_snapshot"),
            rs.getString("status"),
            toEpochMilli(rs.getTimestamp("created_at")),
            toEpochMilli(rs.getTimestamp("updated_at"))
    );

    private static final RowMapper<MemberRecord> MEMBER_ROW_MAPPER = (rs, rowNum) -> new MemberRecord(
            rs.getLong("id"),
            rs.getLong("room_id"),
            rs.getLong("user_id"),
            rs.getString("nickname"),
            rs.getString("avatar_url"),
            rs.getString("track_color"),
            rs.getString("route_points"),
            rs.getString("current_position"),
            rs.getString("completed_missions"),
            rs.getBoolean("is_tracking"),
            toEpochMilli(rs.getTimestamp("last_active_at")),
            toEpochMilli(rs.getTimestamp("created_at"))
    );

    private final JdbcTemplate jdbcTemplate;

    public CoCreateRoomRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public RoomRecord createRoom(String roomCode, Long ownerUserId, String themeSnapshotJson) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    insert into co_create_rooms (
                      room_code, owner_user_id, theme_snapshot, status, created_at, updated_at
                    ) values (?, ?, ?, 'active', now(), now())
                    """,
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, roomCode);
            ps.setLong(2, ownerUserId);
            ps.setString(3, themeSnapshotJson);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("failed_to_create_room");
        }
        return findRoomById(key.longValue()).orElseThrow(() -> new IllegalStateException("created_room_not_found"));
    }

    public Optional<RoomRecord> findRoomByCode(String roomCode) {
        List<RoomRecord> results = jdbcTemplate.query(
                """
                select id, room_code, owner_user_id, theme_snapshot, status, created_at, updated_at
                from co_create_rooms
                where room_code = ? and status = 'active'
                limit 1
                """,
                ROOM_ROW_MAPPER,
                roomCode
        );
        return results.stream().findFirst();
    }

    public Optional<RoomRecord> findRoomById(Long roomId) {
        List<RoomRecord> results = jdbcTemplate.query(
                """
                select id, room_code, owner_user_id, theme_snapshot, status, created_at, updated_at
                from co_create_rooms
                where id = ? and status = 'active'
                limit 1
                """,
                ROOM_ROW_MAPPER,
                roomId
        );
        return results.stream().findFirst();
    }

    public void updateRoomTheme(Long roomId, String themeSnapshotJson) {
        jdbcTemplate.update(
                """
                update co_create_rooms
                set theme_snapshot = ?, updated_at = now()
                where id = ?
                """,
                themeSnapshotJson,
                roomId
        );
    }

    public void changeOwner(Long roomId, Long ownerUserId) {
        jdbcTemplate.update(
                """
                update co_create_rooms
                set owner_user_id = ?, updated_at = now()
                where id = ?
                """,
                ownerUserId,
                roomId
        );
    }

    public void deleteRoom(Long roomId) {
        jdbcTemplate.update("delete from co_create_room_members where room_id = ?", roomId);
        jdbcTemplate.update("delete from co_create_rooms where id = ?", roomId);
    }

    public int countMembers(Long roomId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(1) from co_create_room_members where room_id = ?",
                Integer.class,
                roomId
        );
        return count == null ? 0 : count;
    }

    public Optional<MemberRecord> findMember(Long roomId, Long userId) {
        List<MemberRecord> results = jdbcTemplate.query(
                """
                select id, room_id, user_id, nickname, avatar_url, track_color,
                       route_points, current_position, completed_missions, is_tracking,
                       last_active_at, created_at
                from co_create_room_members
                where room_id = ? and user_id = ?
                limit 1
                """,
                MEMBER_ROW_MAPPER,
                roomId,
                userId
        );
        return results.stream().findFirst();
    }

    public List<MemberRecord> listMembers(Long roomId) {
        return jdbcTemplate.query(
                """
                select id, room_id, user_id, nickname, avatar_url, track_color,
                       route_points, current_position, completed_missions, is_tracking,
                       last_active_at, created_at
                from co_create_room_members
                where room_id = ?
                order by created_at asc
                """,
                MEMBER_ROW_MAPPER,
                roomId
        );
    }

    public void addMember(Long roomId, Long userId, String nickname, String avatarUrl, String trackColor) {
        jdbcTemplate.update(
                """
                insert into co_create_room_members (
                  room_id, user_id, nickname, avatar_url, track_color,
                  route_points, current_position, completed_missions, is_tracking,
                  last_active_at, created_at, updated_at
                ) values (?, ?, ?, ?, ?, '[]', null, '[]', 0, now(), now(), now())
                on duplicate key update
                  nickname = values(nickname),
                  avatar_url = values(avatar_url),
                  track_color = values(track_color),
                  updated_at = now(),
                  last_active_at = now()
                """,
                roomId,
                userId,
                nickname,
                avatarUrl,
                trackColor
        );
    }

    public void updateMemberState(Long roomId,
                                  Long userId,
                                  String routePointsJson,
                                  String currentPositionJson,
                                  String completedMissionsJson,
                                  boolean isTracking) {
        jdbcTemplate.update(
                """
                update co_create_room_members
                set route_points = ?, current_position = ?, completed_missions = ?, is_tracking = ?, updated_at = now(), last_active_at = now()
                where room_id = ? and user_id = ?
                """,
                routePointsJson,
                currentPositionJson,
                completedMissionsJson,
                isTracking,
                roomId,
                userId
        );
    }

    public void deleteMember(Long roomId, Long userId) {
        jdbcTemplate.update("delete from co_create_room_members where room_id = ? and user_id = ?", roomId, userId);
    }

    private static Long toEpochMilli(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toEpochMilli();
    }

    public record RoomRecord(
            Long id,
            String roomCode,
            Long ownerUserId,
            String themeSnapshotJson,
            String status,
            Long createdAt,
            Long updatedAt
    ) {
    }

    public record MemberRecord(
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
