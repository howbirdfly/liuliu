CREATE DATABASE IF NOT EXISTS liuliu_citywalk
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE liuliu_citywalk;

-- 1. 用户表
CREATE TABLE IF NOT EXISTS users (
                                     id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                                     openid VARCHAR(128) DEFAULT NULL COMMENT '小程序openid',
                                     unionid VARCHAR(128) DEFAULT NULL COMMENT '微信unionid，可选',
                                     nickname VARCHAR(100) NOT NULL COMMENT '昵称',
                                     avatar_url VARCHAR(500) DEFAULT NULL COMMENT '头像',
                                     role VARCHAR(32) NOT NULL DEFAULT 'user' COMMENT '角色: user/admin',
                                     status VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT '状态: active/disabled',
                                     source VARCHAR(32) NOT NULL DEFAULT 'miniapp' COMMENT '来源: miniapp/web',
                                     created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                     updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                     last_login_at DATETIME DEFAULT NULL,
                                     PRIMARY KEY (id),
                                     UNIQUE KEY uk_users_openid (openid),
                                     KEY idx_users_source (source),
                                     KEY idx_users_last_login_at (last_login_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 2. 会话表
CREATE TABLE IF NOT EXISTS user_sessions (
                                             id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                                             user_id BIGINT UNSIGNED NOT NULL,
                                             access_token VARCHAR(255) NOT NULL,
                                             refresh_token VARCHAR(255) DEFAULT NULL,
                                             expires_at DATETIME DEFAULT NULL,
                                             client_type VARCHAR(32) NOT NULL DEFAULT 'miniapp' COMMENT 'miniapp/web',
                                             device_info VARCHAR(255) DEFAULT NULL,
                                             created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                             updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                             PRIMARY KEY (id),
                                             UNIQUE KEY uk_user_sessions_access_token (access_token),
                                             UNIQUE KEY uk_user_sessions_refresh_token (refresh_token),
                                             KEY idx_user_sessions_user_id (user_id),
                                             KEY idx_user_sessions_expires_at (expires_at),
                                             CONSTRAINT fk_user_sessions_user_id
                                                 FOREIGN KEY (user_id) REFERENCES users(id)
                                                     ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户会话表';

-- 3. 上传文件表
CREATE TABLE IF NOT EXISTS uploaded_files (
                                              id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                                              user_id BIGINT UNSIGNED DEFAULT NULL,
                                              biz_type VARCHAR(64) NOT NULL DEFAULT 'walk' COMMENT '业务类型',
                                              file_key VARCHAR(255) NOT NULL COMMENT '文件唯一标识',
                                              file_name VARCHAR(255) NOT NULL COMMENT '原始文件名',
                                              file_url VARCHAR(1000) NOT NULL COMMENT '访问地址',
                                              content_type VARCHAR(128) DEFAULT NULL,
                                              file_size BIGINT UNSIGNED DEFAULT 0,
                                              storage_type VARCHAR(32) NOT NULL DEFAULT 'local' COMMENT 'local/oss/cos/s3',
                                              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                              PRIMARY KEY (id),
                                              UNIQUE KEY uk_uploaded_files_file_key (file_key),
                                              KEY idx_uploaded_files_user_id (user_id),
                                              KEY idx_uploaded_files_biz_type (biz_type),
                                              CONSTRAINT fk_uploaded_files_user_id
                                                  FOREIGN KEY (user_id) REFERENCES users(id)
                                                      ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上传文件表';

-- 4. 漫步记录表
CREATE TABLE IF NOT EXISTS walk_records (
                                            id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                                            user_id BIGINT UNSIGNED NOT NULL COMMENT '所属用户ID',
                                            theme_title VARCHAR(255) NOT NULL COMMENT '主题标题',
                                            theme_snapshot JSON NOT NULL COMMENT '主题快照JSON',
                                            location_name VARCHAR(255) DEFAULT NULL COMMENT '地点名称',
                                            location_context VARCHAR(255) DEFAULT NULL COMMENT '地点语境',
                                            route_points JSON DEFAULT NULL COMMENT '轨迹点JSON数组',
                                            missions_completed JSON DEFAULT NULL COMMENT '已完成任务JSON数组',
                                            mission_reviews JSON DEFAULT NULL COMMENT '任务校验结果JSON对象',
                                            photo_list JSON DEFAULT NULL COMMENT '图片URL列表JSON数组',
                                            cover_image VARCHAR(1000) DEFAULT NULL COMMENT '封面图',
                                            note_text TEXT DEFAULT NULL COMMENT '文字记录',
                                            is_public TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否公开',
                                            walk_mode VARCHAR(32) NOT NULL DEFAULT 'pure' COMMENT 'pure/advanced',
                                            generation_source VARCHAR(64) DEFAULT NULL COMMENT '主题来源',
                                            status VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT 'active/deleted',
                                            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                            PRIMARY KEY (id),
                                            KEY idx_walk_records_user_id (user_id),
                                            KEY idx_walk_records_is_public_created_at (is_public, created_at),
                                            KEY idx_walk_records_created_at (created_at),
                                            KEY idx_walk_records_status (status),
                                            CONSTRAINT fk_walk_records_user_id
                                                FOREIGN KEY (user_id) REFERENCES users(id)
                                                    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='漫步记录表';

-- 5. 社区评论表
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

-- 5. 主题表（可选，给后面社区主题/收藏主题留口子）
CREATE TABLE IF NOT EXISTS walk_themes (
                                           id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                                           user_id BIGINT UNSIGNED DEFAULT NULL COMMENT '创建人',
                                           title VARCHAR(255) NOT NULL,
                                           description TEXT DEFAULT NULL,
                                           category VARCHAR(64) DEFAULT NULL,
                                           missions JSON DEFAULT NULL,
                                           vibe_color VARCHAR(32) DEFAULT NULL,
                                           source VARCHAR(64) DEFAULT NULL COMMENT 'preset/ai/random/combined',
                                           status VARCHAR(32) NOT NULL DEFAULT 'active',
                                           created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                           updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                           PRIMARY KEY (id),
                                           KEY idx_walk_themes_user_id (user_id),
                                           KEY idx_walk_themes_status (status),
                                           CONSTRAINT fk_walk_themes_user_id
                                               FOREIGN KEY (user_id) REFERENCES users(id)
                                                   ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主题表';
