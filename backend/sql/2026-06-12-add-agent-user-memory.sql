USE liuliu_citywalk;

CREATE TABLE IF NOT EXISTS agent_user_memory (
    user_id BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    preferred_cities TEXT DEFAULT NULL COMMENT '偏好城市 JSON 数组',
    preferred_areas TEXT DEFAULT NULL COMMENT '偏好区域 JSON 数组',
    walk_styles TEXT DEFAULT NULL COMMENT '偏好路线风格 JSON 数组',
    preferred_duration VARCHAR(64) DEFAULT NULL COMMENT '偏好时长',
    mobility_level VARCHAR(64) DEFAULT NULL COMMENT '体力/节奏偏好',
    avoid_tags TEXT DEFAULT NULL COMMENT '避雷点 JSON 数组',
    recent_suggested_areas TEXT DEFAULT NULL COMMENT '最近推荐过的区域 JSON 数组',
    summary TEXT DEFAULT NULL COMMENT '记忆摘要',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_agent_user_memory_user_id
        FOREIGN KEY (user_id) REFERENCES users(id)
            ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 用户长时记忆';
