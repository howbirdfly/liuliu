create table co_create_room_members
(
    id                 bigint auto_increment
        primary key,
    room_id            bigint                               not null,
    user_id            bigint                               not null,
    nickname           varchar(64)                          not null,
    avatar_url         varchar(512)                         null,
    track_color        varchar(32)                          not null,
    route_points       text                                 null,
    current_position   text                                 null,
    completed_missions text                                 null,
    is_tracking        tinyint(1) default 0                 not null,
    last_active_at     datetime   default CURRENT_TIMESTAMP not null,
    created_at         datetime   default CURRENT_TIMESTAMP not null,
    updated_at         datetime   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    constraint uk_co_create_room_member
        unique (room_id, user_id)
);

create index idx_co_create_room_members_room_id
    on co_create_room_members (room_id);

create index idx_co_create_room_members_user_id
    on co_create_room_members (user_id);

create table co_create_rooms
(
    id             bigint auto_increment
        primary key,
    room_code      varchar(16)                           not null,
    owner_user_id  bigint                                not null,
    theme_snapshot text                                  null,
    status         varchar(16) default 'active'          not null,
    created_at     datetime    default CURRENT_TIMESTAMP not null,
    updated_at     datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    constraint room_code
        unique (room_code)
);

create index idx_co_create_rooms_owner_user_id
    on co_create_rooms (owner_user_id);

create table email_verification_codes
(
    id         bigint unsigned auto_increment
        primary key,
    email      varchar(255)                       not null,
    code_hash  varchar(255)                       not null,
    expires_at datetime                           not null,
    used_at    datetime                           null,
    created_at datetime default CURRENT_TIMESTAMP not null,
    updated_at datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP
)
    comment '邮箱验证码记录' charset = utf8mb4;

create index idx_email_verification_email
    on email_verification_codes (email);

create index idx_email_verification_expires
    on email_verification_codes (expires_at);

create table user_search_history
(
    id         bigint unsigned auto_increment
        primary key,
    user_id    bigint unsigned                    not null,
    keyword    varchar(128)                       not null,
    created_at datetime default CURRENT_TIMESTAMP not null
);

create index idx_user_search
    on user_search_history (user_id, created_at);

create table users
(
    id            bigint unsigned auto_increment comment '主键ID'
        primary key,
    openid        varchar(128)                           null comment '小程序openid',
    unionid       varchar(128)                           null comment '微信unionid，可选',
    nickname      varchar(100)                           not null comment '昵称',
    avatar_url    varchar(500)                           null comment '头像',
    bio           varchar(500) default ''                not null comment '????',
    role          varchar(32)  default 'user'            not null comment '角色: user/admin',
    status        varchar(32)  default 'active'          not null comment '状态: active/disabled',
    source        varchar(32)  default 'miniapp'         not null comment '来源: miniapp/web',
    created_at    datetime     default CURRENT_TIMESTAMP not null,
    updated_at    datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    last_login_at datetime                               null,
    constraint uk_users_openid
        unique (openid)
)
    comment '用户表' charset = utf8mb4;

create table agent_user_memory
(
    user_id                bigint unsigned                    not null comment '用户ID'
        primary key,
    preferred_cities       text                               null comment '偏好城市 JSON 数组',
    preferred_areas        text                               null comment '偏好区域 JSON 数组',
    walk_styles            text                               null comment '偏好路线风格 JSON 数组',
    preferred_duration     varchar(64)                        null comment '偏好时长',
    mobility_level         varchar(64)                        null comment '体力/节奏偏好',
    avoid_tags             text                               null comment '避雷点 JSON 数组',
    recent_suggested_areas text                               null comment '最近推荐过的区域 JSON 数组',
    summary                text                               null comment '记忆摘要',
    created_at             datetime default CURRENT_TIMESTAMP not null,
    updated_at             datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    constraint fk_agent_user_memory_user_id
        foreign key (user_id) references users (id)
            on delete cascade
)
    comment 'Agent 用户长时记忆' charset = utf8mb4;

create table uploaded_files
(
    id           bigint unsigned auto_increment
        primary key,
    user_id      bigint unsigned                           null,
    biz_type     varchar(64)     default 'walk'            not null comment '业务类型',
    file_key     varchar(255)                              not null comment '文件唯一标识',
    file_name    varchar(255)                              not null comment '原始文件名',
    file_url     varchar(1000)                             not null comment '访问地址',
    content_type varchar(128)                              null,
    file_size    bigint unsigned default '0'               null,
    storage_type varchar(32)     default 'local'           not null comment 'local/oss/cos/s3',
    created_at   datetime        default CURRENT_TIMESTAMP not null,
    constraint uk_uploaded_files_file_key
        unique (file_key),
    constraint fk_uploaded_files_user_id
        foreign key (user_id) references users (id)
            on delete set null
)
    comment '上传文件表' charset = utf8mb4;

create index idx_uploaded_files_biz_type
    on uploaded_files (biz_type);

create index idx_uploaded_files_user_id
    on uploaded_files (user_id);

create table user_credentials
(
    id            bigint unsigned auto_increment
        primary key,
    user_id       bigint unsigned                    not null,
    email         varchar(255)                       not null,
    password_hash varchar(255)                       not null,
    created_at    datetime default CURRENT_TIMESTAMP not null,
    updated_at    datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    constraint uk_user_credentials_email
        unique (email),
    constraint fk_user_credentials_user_id
        foreign key (user_id) references users (id)
            on delete cascade
)
    comment '用户邮箱凭证' charset = utf8mb4;

create index idx_user_credentials_user_id
    on user_credentials (user_id);

create table user_sessions
(
    id            bigint unsigned auto_increment
        primary key,
    user_id       bigint unsigned                       not null,
    access_token  varchar(255)                          not null,
    refresh_token varchar(255)                          null,
    expires_at    datetime                              null,
    client_type   varchar(32) default 'miniapp'         not null comment 'miniapp/web',
    device_info   varchar(255)                          null,
    created_at    datetime    default CURRENT_TIMESTAMP not null,
    updated_at    datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    constraint uk_user_sessions_access_token
        unique (access_token),
    constraint uk_user_sessions_refresh_token
        unique (refresh_token),
    constraint fk_user_sessions_user_id
        foreign key (user_id) references users (id)
            on delete cascade
)
    comment '用户会话表' charset = utf8mb4;

create index idx_user_sessions_expires_at
    on user_sessions (expires_at);

create index idx_user_sessions_user_id
    on user_sessions (user_id);

create index idx_users_last_login_at
    on users (last_login_at);

create index idx_users_source
    on users (source);

create table walk_record_favorites
(
    id         bigint unsigned auto_increment
        primary key,
    walk_id    bigint unsigned                    not null,
    user_id    bigint unsigned                    not null,
    created_at datetime default CURRENT_TIMESTAMP not null,
    constraint uk_walk_favorite
        unique (walk_id, user_id)
);

create index idx_walk_favorite_user
    on walk_record_favorites (user_id);

create table walk_record_likes
(
    id         bigint unsigned auto_increment
        primary key,
    walk_id    bigint unsigned                    not null,
    user_id    bigint unsigned                    not null,
    created_at datetime default CURRENT_TIMESTAMP not null,
    constraint uk_walk_like
        unique (walk_id, user_id)
);

create index idx_walk_like_user
    on walk_record_likes (user_id);

create table walk_record_tags
(
    id         bigint unsigned auto_increment
        primary key,
    walk_id    bigint unsigned                    not null,
    tag_name   varchar(64)                        not null,
    created_at datetime default CURRENT_TIMESTAMP not null
);

create index idx_tag_name
    on walk_record_tags (tag_name);

create index idx_walk_tag
    on walk_record_tags (walk_id);

create table walk_records
(
    id                 bigint unsigned auto_increment
        primary key,
    user_id            bigint unsigned                       not null comment '所属用户ID',
    theme_title        varchar(255)                          not null comment '主题标题',
    theme_snapshot     json                                  not null comment '主题快照JSON',
    location_name      varchar(255)                          null comment '地点名称',
    location_context   varchar(255)                          null comment '地点语境',
    route_points       json                                  null comment '轨迹点JSON数组',
    missions_completed json                                  null comment '已完成任务JSON数组',
    mission_reviews    json                                  null comment '任务校验结果JSON对象',
    photo_list         json                                  null comment '图片URL列表JSON数组',
    cover_image        varchar(1000)                         null comment '封面图',
    note_text          text                                  null comment '文字记录',
    is_public          tinyint(1)  default 1                 not null comment '是否公开',
    walk_mode          varchar(32) default 'pure'            not null comment 'pure/advanced',
    generation_source  varchar(64)                           null comment '主题来源',
    status             varchar(32) default 'active'          not null comment 'active/deleted',
    created_at         datetime    default CURRENT_TIMESTAMP not null,
    updated_at         datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    like_count         int         default 0                 not null,
    favorite_count     int         default 0                 not null,
    view_count         int         default 0                 not null,
    constraint fk_walk_records_user_id
        foreign key (user_id) references users (id)
            on delete cascade
)
    comment '漫步记录表' charset = utf8mb4;

create table walk_record_comments
(
    id         bigint unsigned auto_increment
        primary key,
    walk_id    bigint unsigned                       not null comment '所属帖子ID',
    parent_id  bigint unsigned                       null comment '父评论ID，空为顶级评论',
    user_id    bigint unsigned                       not null comment '评论作者ID',
    content    text                                  not null comment '评论内容',
    status     varchar(32) default 'active'          not null comment 'active/deleted',
    created_at datetime    default CURRENT_TIMESTAMP not null,
    updated_at datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    constraint fk_walk_record_comments_parent_id
        foreign key (parent_id) references walk_record_comments (id)
            on delete cascade,
    constraint fk_walk_record_comments_user_id
        foreign key (user_id) references users (id)
            on delete cascade,
    constraint fk_walk_record_comments_walk_id
        foreign key (walk_id) references walk_records (id)
            on delete cascade
)
    comment '社区评论表' charset = utf8mb4;

create table user_notifications
(
    id                bigint unsigned auto_increment
        primary key,
    recipient_user_id bigint unsigned                      not null,
    actor_user_id     bigint unsigned                      not null,
    type              varchar(64)                          not null,
    walk_id           bigint unsigned                      null,
    comment_id        bigint unsigned                      null,
    is_read           tinyint(1) default 0                 not null,
    created_at        datetime   default CURRENT_TIMESTAMP not null,
    updated_at        datetime   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    constraint fk_user_notifications_actor_user_id
        foreign key (actor_user_id) references users (id)
            on delete cascade,
    constraint fk_user_notifications_comment_id
        foreign key (comment_id) references walk_record_comments (id)
            on delete cascade,
    constraint fk_user_notifications_recipient_user_id
        foreign key (recipient_user_id) references users (id)
            on delete cascade,
    constraint fk_user_notifications_walk_id
        foreign key (walk_id) references walk_records (id)
            on delete cascade
)
    comment '社区通知表' charset = utf8mb4;

create index idx_user_notifications_actor_user_id
    on user_notifications (actor_user_id);

create index idx_user_notifications_comment_id
    on user_notifications (comment_id);

create index idx_user_notifications_recipient_read
    on user_notifications (recipient_user_id, is_read, created_at);

create index idx_user_notifications_walk_id
    on user_notifications (walk_id);

create index idx_walk_record_comments_created_at
    on walk_record_comments (created_at);

create index idx_walk_record_comments_parent_id
    on walk_record_comments (parent_id);

create index idx_walk_record_comments_user_id
    on walk_record_comments (user_id);

create index idx_walk_record_comments_walk_id
    on walk_record_comments (walk_id);

create index idx_walk_records_created_at
    on walk_records (created_at);

create index idx_walk_records_is_public_created_at
    on walk_records (is_public, created_at);

create index idx_walk_records_status
    on walk_records (status);

create index idx_walk_records_user_id
    on walk_records (user_id);

create table walk_themes
(
    id          bigint unsigned auto_increment
        primary key,
    user_id     bigint unsigned                       null comment '创建人',
    title       varchar(255)                          not null,
    description text                                  null,
    category    varchar(64)                           null,
    missions    json                                  null,
    vibe_color  varchar(32)                           null,
    source      varchar(64)                           null comment 'preset/ai/random/combined',
    status      varchar(32) default 'active'          not null,
    created_at  datetime    default CURRENT_TIMESTAMP not null,
    updated_at  datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    constraint fk_walk_themes_user_id
        foreign key (user_id) references users (id)
            on delete set null
)
    comment '主题表' charset = utf8mb4;

create index idx_walk_themes_status
    on walk_themes (status);

create index idx_walk_themes_user_id
    on walk_themes (user_id);

