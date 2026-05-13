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

create table users
(
    id            bigint unsigned auto_increment comment '主键ID'
        primary key,
    openid        varchar(128)                          null comment '小程序openid',
    unionid       varchar(128)                          null comment '微信unionid，可选',
    nickname      varchar(100)                          not null comment '昵称',
    avatar_url    varchar(500)                          null comment '头像',
    role          varchar(32) default 'user'            not null comment '角色: user/admin',
    status        varchar(32) default 'active'          not null comment '状态: active/disabled',
    source        varchar(32) default 'miniapp'         not null comment '来源: miniapp/web',
    created_at    datetime    default CURRENT_TIMESTAMP not null,
    updated_at    datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    last_login_at datetime                              null,
    constraint uk_users_openid
        unique (openid)
)
    comment '用户表' charset = utf8mb4;

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
    constraint fk_walk_records_user_id
        foreign key (user_id) references users (id)
            on delete cascade
)
    comment '漫步记录表' charset = utf8mb4;

create index idx_walk_records_created_at
    on walk_records (created_at);

create index idx_walk_records_is_public_created_at
    on walk_records (is_public, created_at);

create index idx_walk_records_status
    on walk_records (status);

create index idx_walk_records_user_id
    on walk_records (user_id);

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
    constraint fk_walk_record_comments_walk_id
        foreign key (walk_id) references walk_records (id)
            on delete cascade,
    constraint fk_walk_record_comments_parent_id
        foreign key (parent_id) references walk_record_comments (id)
            on delete cascade,
    constraint fk_walk_record_comments_user_id
        foreign key (user_id) references users (id)
            on delete cascade
)
    comment '社区评论表' charset = utf8mb4;

create index idx_walk_record_comments_walk_id
    on walk_record_comments (walk_id);

create index idx_walk_record_comments_parent_id
    on walk_record_comments (parent_id);

create index idx_walk_record_comments_user_id
    on walk_record_comments (user_id);

create index idx_walk_record_comments_created_at
    on walk_record_comments (created_at);

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

