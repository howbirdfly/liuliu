-- 社区互动能力补齐（只升级，不重建）
-- 目标库：liuliu_citywalk

USE liuliu_citywalk;

-- 1) walk_records 统计字段补齐
SET @has_like_count := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'walk_records'
      AND COLUMN_NAME = 'like_count'
);
SET @sql_like_count := IF(
    @has_like_count = 0,
    'ALTER TABLE walk_records ADD COLUMN like_count INT UNSIGNED NOT NULL DEFAULT 0',
    'SELECT ''skip: walk_records.like_count exists'''
);
PREPARE stmt_like_count FROM @sql_like_count;
EXECUTE stmt_like_count;
DEALLOCATE PREPARE stmt_like_count;

SET @has_favorite_count := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'walk_records'
      AND COLUMN_NAME = 'favorite_count'
);
SET @sql_favorite_count := IF(
    @has_favorite_count = 0,
    'ALTER TABLE walk_records ADD COLUMN favorite_count INT UNSIGNED NOT NULL DEFAULT 0',
    'SELECT ''skip: walk_records.favorite_count exists'''
);
PREPARE stmt_favorite_count FROM @sql_favorite_count;
EXECUTE stmt_favorite_count;
DEALLOCATE PREPARE stmt_favorite_count;

SET @has_view_count := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'walk_records'
      AND COLUMN_NAME = 'view_count'
);
SET @sql_view_count := IF(
    @has_view_count = 0,
    'ALTER TABLE walk_records ADD COLUMN view_count INT UNSIGNED NOT NULL DEFAULT 0',
    'SELECT ''skip: walk_records.view_count exists'''
);
PREPARE stmt_view_count FROM @sql_view_count;
EXECUTE stmt_view_count;
DEALLOCATE PREPARE stmt_view_count;

-- 2) 点赞表
CREATE TABLE IF NOT EXISTS walk_record_likes (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    walk_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_walk_record_likes_walk_user (walk_id, user_id),
    KEY idx_walk_record_likes_user_id (user_id),
    KEY idx_walk_record_likes_created_at (created_at),
    CONSTRAINT fk_walk_record_likes_walk_id
        FOREIGN KEY (walk_id) REFERENCES walk_records(id)
            ON DELETE CASCADE,
    CONSTRAINT fk_walk_record_likes_user_id
        FOREIGN KEY (user_id) REFERENCES users(id)
            ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖子点赞表';

-- 3) 收藏表
CREATE TABLE IF NOT EXISTS walk_record_favorites (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    walk_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_walk_record_favorites_walk_user (walk_id, user_id),
    KEY idx_walk_record_favorites_user_id (user_id),
    KEY idx_walk_record_favorites_created_at (created_at),
    CONSTRAINT fk_walk_record_favorites_walk_id
        FOREIGN KEY (walk_id) REFERENCES walk_records(id)
            ON DELETE CASCADE,
    CONSTRAINT fk_walk_record_favorites_user_id
        FOREIGN KEY (user_id) REFERENCES users(id)
            ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖子收藏表';

-- 4) 标签表
CREATE TABLE IF NOT EXISTS walk_record_tags (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    walk_id BIGINT UNSIGNED NOT NULL,
    tag_name VARCHAR(64) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_walk_record_tags_walk_tag (walk_id, tag_name),
    KEY idx_walk_record_tags_tag_name (tag_name),
    CONSTRAINT fk_walk_record_tags_walk_id
        FOREIGN KEY (walk_id) REFERENCES walk_records(id)
            ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖子标签表';

