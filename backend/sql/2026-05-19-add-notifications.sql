USE liuliu_citywalk;

CREATE TABLE IF NOT EXISTS user_notifications (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    recipient_user_id BIGINT UNSIGNED NOT NULL,
    actor_user_id BIGINT UNSIGNED NOT NULL,
    type VARCHAR(64) NOT NULL,
    walk_id BIGINT UNSIGNED DEFAULT NULL,
    comment_id BIGINT UNSIGNED DEFAULT NULL,
    is_read TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_notifications_recipient_read (recipient_user_id, is_read, created_at),
    KEY idx_user_notifications_actor_user_id (actor_user_id),
    KEY idx_user_notifications_walk_id (walk_id),
    KEY idx_user_notifications_comment_id (comment_id),
    CONSTRAINT fk_user_notifications_recipient_user_id
        FOREIGN KEY (recipient_user_id) REFERENCES users(id)
            ON DELETE CASCADE,
    CONSTRAINT fk_user_notifications_actor_user_id
        FOREIGN KEY (actor_user_id) REFERENCES users(id)
            ON DELETE CASCADE,
    CONSTRAINT fk_user_notifications_walk_id
        FOREIGN KEY (walk_id) REFERENCES walk_records(id)
            ON DELETE CASCADE,
    CONSTRAINT fk_user_notifications_comment_id
        FOREIGN KEY (comment_id) REFERENCES walk_record_comments(id)
            ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='社区通知表';
