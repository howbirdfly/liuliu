package com.liuliu.citywalk.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UploadedFileRepository {

    private final JdbcTemplate jdbcTemplate;

    public UploadedFileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(Long userId, String bizType, String fileKey, String fileName, String fileUrl, String contentType, Long fileSize) {
        jdbcTemplate.update(
                """
                insert into uploaded_files (user_id, biz_type, file_key, file_name, file_url, content_type, file_size, storage_type, created_at)
                values (?, ?, ?, ?, ?, ?, ?, 'oss', now())
                """,
                userId,
                bizType,
                fileKey,
                fileName,
                fileUrl,
                contentType,
                fileSize
        );
    }
}
