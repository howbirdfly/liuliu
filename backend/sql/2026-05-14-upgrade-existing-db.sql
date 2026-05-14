-- 只升级，不重建（可重复执行）
-- 目标库：liuliu_citywalk

USE liuliu_citywalk;

-- 1) users 表补充 bio 字段（若不存在）
SET @bio_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'users'
      AND COLUMN_NAME = 'bio'
);
SET @bio_sql := IF(
    @bio_exists = 0,
    'ALTER TABLE users ADD COLUMN bio VARCHAR(500) NOT NULL DEFAULT '''' COMMENT ''个人简介'' AFTER avatar_url',
    'SELECT ''skip: users.bio exists'''
);
PREPARE stmt_bio FROM @bio_sql;
EXECUTE stmt_bio;
DEALLOCATE PREPARE stmt_bio;

-- 2) 社区评论表（若不存在）
CREATE TABLE IF NOT EXISTS walk_record_comments (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    walk_id BIGINT UNSIGNED NOT NULL COMMENT '所属帖子ID',
    parent_id BIGINT UNSIGNED DEFAULT NULL COMMENT '父评论ID，空为顶级评论',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '评论作者ID',
    content TEXT NOT NULL COMMENT '评论内容',
    status VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT 'active/deleted',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_walk_record_comments_walk_id (walk_id),
    KEY idx_walk_record_comments_parent_id (parent_id),
    KEY idx_walk_record_comments_user_id (user_id),
    KEY idx_walk_record_comments_created_at (created_at),
    CONSTRAINT fk_walk_record_comments_walk_id
        FOREIGN KEY (walk_id) REFERENCES walk_records(id)
            ON DELETE CASCADE,
    CONSTRAINT fk_walk_record_comments_parent_id
        FOREIGN KEY (parent_id) REFERENCES walk_record_comments(id)
            ON DELETE CASCADE,
    CONSTRAINT fk_walk_record_comments_user_id
        FOREIGN KEY (user_id) REFERENCES users(id)
            ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='社区评论表';

-- 3) 邮箱登录相关表（若不存在）
CREATE TABLE IF NOT EXISTS user_credentials (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_credentials_email (email),
    KEY idx_user_credentials_user_id (user_id),
    CONSTRAINT fk_user_credentials_user_id
        FOREIGN KEY (user_id) REFERENCES users(id)
            ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户邮箱凭证';

CREATE TABLE IF NOT EXISTS email_verification_codes (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL,
    code_hash VARCHAR(255) NOT NULL,
    expires_at DATETIME NOT NULL,
    used_at DATETIME DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_email_verification_email (email),
    KEY idx_email_verification_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邮箱验证码记录';

