package com.liuliu.citywalk.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class UserSessionRepository {

    private static final RowMapper<UserSessionRecord> ROW_MAPPER = (rs, rowNum) -> new UserSessionRecord(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getString("access_token"),
            rs.getString("refresh_token"),
            toEpochMilli(rs.getTimestamp("expires_at"))
    );

    private final JdbcTemplate jdbcTemplate;

    public UserSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void createSession(Long userId, String accessToken, String refreshToken, long expiresInSeconds, String clientType) {
        jdbcTemplate.update(
                """
                insert into user_sessions (user_id, access_token, refresh_token, expires_at, client_type, created_at, updated_at)
                values (?, ?, ?, date_add(now(), interval ? second), ?, now(), now())
                """,
                userId,
                accessToken,
                refreshToken,
                expiresInSeconds,
                clientType
        );
    }

    public Optional<UserSessionRecord> findValidByAccessToken(String accessToken) {
        List<UserSessionRecord> results = jdbcTemplate.query(
                """
                select id, user_id, access_token, refresh_token, expires_at
                from user_sessions
                where access_token = ?
                  and (expires_at is null or expires_at > now())
                order by id desc
                limit 1
                """,
                ROW_MAPPER,
                accessToken
        );
        return results.stream().findFirst();
    }

    private static Long toEpochMilli(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toEpochMilli();
    }

    public record UserSessionRecord(
            Long id,
            Long userId,
            String accessToken,
            String refreshToken,
            Long expiresAt
    ) {
    }
}
