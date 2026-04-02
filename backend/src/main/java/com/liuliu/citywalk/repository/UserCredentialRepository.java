package com.liuliu.citywalk.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class UserCredentialRepository {

    private static final RowMapper<UserCredentialRecord> ROW_MAPPER = (rs, rowNum) -> new UserCredentialRecord(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getString("email"),
            rs.getString("password_hash")
    );

    private final JdbcTemplate jdbcTemplate;

    public UserCredentialRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        ensureTableExists();
    }

    public Optional<UserCredentialRecord> findByEmail(String email) {
        List<UserCredentialRecord> results = jdbcTemplate.query(
                """
                select id, user_id, email, password_hash
                from user_credentials
                where email = ?
                limit 1
                """,
                ROW_MAPPER,
                email
        );
        return results.stream().findFirst();
    }

    public Long createUser(String email, String nickname, String avatarUrl) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    insert into users (openid, nickname, avatar_url, role, status, source, created_at, updated_at, last_login_at)
                    values (?, ?, ?, 'user', 'active', 'web', now(), now(), now())
                    """,
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, email);
            ps.setString(2, nickname);
            ps.setString(3, avatarUrl);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("failed_to_create_user");
        }
        return key.longValue();
    }

    public void createCredential(Long userId, String email, String passwordHash) {
        jdbcTemplate.update(
                """
                insert into user_credentials (user_id, email, password_hash, created_at, updated_at)
                values (?, ?, ?, now(), now())
                """,
                userId,
                email,
                passwordHash
        );
    }

    public void updatePassword(Long userId, String passwordHash) {
        jdbcTemplate.update(
                """
                update user_credentials
                set password_hash = ?, updated_at = now()
                where user_id = ?
                """,
                passwordHash,
                userId
        );
    }

    private void ensureTableExists() {
        jdbcTemplate.execute(
                """
                create table if not exists user_credentials (
                    id bigint unsigned not null auto_increment,
                    user_id bigint unsigned not null,
                    email varchar(255) not null,
                    password_hash varchar(255) not null,
                    created_at datetime not null default current_timestamp,
                    updated_at datetime not null default current_timestamp on update current_timestamp,
                    primary key (id),
                    unique key uk_user_credentials_email (email),
                    key idx_user_credentials_user_id (user_id),
                    constraint fk_user_credentials_user_id
                      foreign key (user_id) references users(id)
                        on delete cascade
                ) engine=InnoDB default charset=utf8mb4 comment='用户邮箱凭证';
                """
        );
    }

    public record UserCredentialRecord(
            Long id,
            Long userId,
            String email,
            String passwordHash
    ) {
    }
}
