package com.liuliu.citywalk.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class EmailVerificationRepository {

    private static final RowMapper<EmailVerificationRecord> ROW_MAPPER = (rs, rowNum) -> new EmailVerificationRecord(
            rs.getLong("id"),
            rs.getString("email"),
            rs.getString("code_hash"),
            rs.getTimestamp("expires_at"),
            rs.getTimestamp("used_at"),
            rs.getTimestamp("created_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public EmailVerificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<EmailVerificationRecord> findLatestValid(String email) {
        List<EmailVerificationRecord> results = jdbcTemplate.query(
                """
                select id, email, code_hash, expires_at, used_at, created_at
                from email_verification_codes
                where email = ?
                  and used_at is null
                  and expires_at > now()
                order by id desc
                limit 1
                """,
                ROW_MAPPER,
                email
        );
        return results.stream().findFirst();
    }

    public Optional<EmailVerificationRecord> findLatest(String email) {
        List<EmailVerificationRecord> results = jdbcTemplate.query(
                """
                select id, email, code_hash, expires_at, used_at, created_at
                from email_verification_codes
                where email = ?
                order by id desc
                limit 1
                """,
                ROW_MAPPER,
                email
        );
        return results.stream().findFirst();
    }

    public void create(String email, String codeHash, Instant expiresAt) {
        jdbcTemplate.update(
                """
                insert into email_verification_codes (email, code_hash, expires_at, created_at, updated_at)
                values (?, ?, ?, now(), now())
                """,
                email,
                codeHash,
                Timestamp.from(expiresAt)
        );
    }

    public void markUsed(Long id) {
        jdbcTemplate.update(
                "update email_verification_codes set used_at = now(), updated_at = now() where id = ?",
                id
        );
    }

    public record EmailVerificationRecord(
            Long id,
            String email,
            String codeHash,
            Timestamp expiresAt,
            Timestamp usedAt,
            Timestamp createdAt
    ) {
    }
}
