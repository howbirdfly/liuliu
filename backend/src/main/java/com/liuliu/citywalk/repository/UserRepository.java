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
public class UserRepository {

    private static final RowMapper<UserRecord> ROW_MAPPER = (rs, rowNum) -> new UserRecord(
            rs.getLong("id"),
            rs.getString("openid"),
            rs.getString("nickname"),
            rs.getString("avatar_url"),
            rs.getString("role"),
            toEpochMilli(rs.getTimestamp("created_at")),
            toEpochMilli(rs.getTimestamp("last_login_at"))
    );

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<UserRecord> findByOpenid(String openid) {
        List<UserRecord> results = jdbcTemplate.query(
                """
                select id, openid, nickname, avatar_url, role, created_at, last_login_at
                from users
                where openid = ?
                limit 1
                """,
                ROW_MAPPER,
                openid
        );
        return results.stream().findFirst();
    }

    public Optional<UserRecord> findById(Long id) {
        List<UserRecord> results = jdbcTemplate.query(
                """
                select id, openid, nickname, avatar_url, role, created_at, last_login_at
                from users
                where id = ?
                limit 1
                """,
                ROW_MAPPER,
                id
        );
        return results.stream().findFirst();
    }

    public UserRecord create(String openid, String nickname, String avatarUrl) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    insert into users (openid, nickname, avatar_url, role, status, source, created_at, updated_at, last_login_at)
                    values (?, ?, ?, 'user', 'active', 'miniapp', now(), now(), now())
                    """,
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, openid);
            ps.setString(2, nickname);
            ps.setString(3, avatarUrl);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("failed_to_create_user");
        }
        return findById(key.longValue()).orElseThrow(() -> new IllegalStateException("created_user_not_found"));
    }

    public UserRecord updateProfileAndLogin(Long id, String nickname, String avatarUrl) {
        jdbcTemplate.update(
                """
                update users
                set nickname = ?, avatar_url = ?, source = 'miniapp', updated_at = now(), last_login_at = now()
                where id = ?
                """,
                nickname,
                avatarUrl,
                id
        );
        return findById(id).orElseThrow(() -> new IllegalStateException("updated_user_not_found"));
    }

    public UserRecord updateProfile(Long id, String nickname, String avatarUrl) {
        jdbcTemplate.update(
                """
                update users
                set nickname = ?, avatar_url = ?, updated_at = now()
                where id = ?
                """,
                nickname,
                avatarUrl,
                id
        );
        return findById(id).orElseThrow(() -> new IllegalStateException("updated_user_not_found"));
    }

    private static Long toEpochMilli(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toEpochMilli();
    }

    public record UserRecord(
            Long id,
            String openid,
            String nickname,
            String avatarUrl,
            String role,
            Long createdAt,
            Long lastLoginAt
    ) {
    }
}
