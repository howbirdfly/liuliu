package com.liuliu.citywalk.service;

import com.liuliu.citywalk.mapper.CommunityCommentMapper;
import com.liuliu.citywalk.mapper.UserNotificationMapper;
import com.liuliu.citywalk.mapper.WalkRecordMapper;
import com.liuliu.citywalk.mapper.entity.CommunityCommentQueryRow;
import com.liuliu.citywalk.mapper.entity.UserNotificationEntity;
import com.liuliu.citywalk.mapper.entity.UserNotificationQueryRow;
import com.liuliu.citywalk.mapper.entity.WalkRecordEntity;
import com.liuliu.citywalk.model.dto.response.NotificationUnreadCountResponse;
import com.liuliu.citywalk.model.dto.response.UserNotificationResponse;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;

@Service
public class NotificationService {

    public static final String TYPE_POST_COMMENTED = "post_commented";
    public static final String TYPE_COMMENT_REPLIED = "comment_replied";
    public static final String TYPE_POST_LIKED = "post_liked";
    public static final String TYPE_POST_FAVORITED = "post_favorited";
    private static final String DEFAULT_ACTOR_NAME = "Community Walker";

    private final UserNotificationMapper userNotificationMapper;
    private final WalkRecordMapper walkRecordMapper;
    private final CommunityCommentMapper communityCommentMapper;

    public NotificationService(
            UserNotificationMapper userNotificationMapper,
            WalkRecordMapper walkRecordMapper,
            CommunityCommentMapper communityCommentMapper
    ) {
        this.userNotificationMapper = userNotificationMapper;
        this.walkRecordMapper = walkRecordMapper;
        this.communityCommentMapper = communityCommentMapper;
    }

    public List<UserNotificationResponse> listNotifications(Long userId, int page, int pageSize) {
        int limit = normalizePageSize(pageSize);
        int offset = normalizeOffset(page, limit);
        return userNotificationMapper.findByRecipientUserId(userId, limit, offset).stream()
                .map(this::toResponse)
                .toList();
    }

    public NotificationUnreadCountResponse unreadCount(Long userId) {
        Integer count = userNotificationMapper.countUnreadByRecipientUserId(userId);
        return new NotificationUnreadCountResponse(count == null ? 0L : count.longValue());
    }

    public void markRead(Long notificationId, Long userId) {
        userNotificationMapper.markRead(notificationId, userId);
    }

    public void markAllRead(Long userId) {
        userNotificationMapper.markAllRead(userId);
    }

    public void notifyCommentCreated(Long walkId, Long actorUserId, Long commentId, Long parentCommentId) {
        WalkRecordEntity walk = walkRecordMapper.findPublicActiveById(walkId);
        if (walk == null) {
            return;
        }

        createNotificationIfNeeded(walk.getUserId(), actorUserId, TYPE_POST_COMMENTED, walkId, commentId);

        if (parentCommentId == null) {
            return;
        }

        CommunityCommentQueryRow parentComment = communityCommentMapper.findActiveById(parentCommentId);
        if (parentComment == null) {
            return;
        }

        Long parentAuthorId = parentComment.getUserId();
        if (parentAuthorId == null
                || Objects.equals(parentAuthorId, actorUserId)
                || Objects.equals(parentAuthorId, walk.getUserId())) {
            return;
        }

        createNotificationIfNeeded(parentAuthorId, actorUserId, TYPE_COMMENT_REPLIED, walkId, commentId);
    }

    public void notifyWalkLiked(Long walkId, Long actorUserId) {
        WalkRecordEntity walk = walkRecordMapper.findPublicActiveById(walkId);
        if (walk == null) {
            return;
        }
        createNotificationIfNeeded(walk.getUserId(), actorUserId, TYPE_POST_LIKED, walkId, null);
    }

    public void notifyWalkFavorited(Long walkId, Long actorUserId) {
        WalkRecordEntity walk = walkRecordMapper.findPublicActiveById(walkId);
        if (walk == null) {
            return;
        }
        createNotificationIfNeeded(walk.getUserId(), actorUserId, TYPE_POST_FAVORITED, walkId, null);
    }

    private void createNotificationIfNeeded(Long recipientUserId, Long actorUserId, String type, Long walkId, Long commentId) {
        if (recipientUserId == null || recipientUserId <= 0) {
            return;
        }
        if (actorUserId == null || actorUserId <= 0) {
            return;
        }
        if (recipientUserId.equals(actorUserId)) {
            return;
        }

        UserNotificationEntity entity = new UserNotificationEntity();
        entity.setRecipientUserId(recipientUserId);
        entity.setActorUserId(actorUserId);
        entity.setType(type);
        entity.setWalkId(walkId);
        entity.setCommentId(commentId);
        entity.setIsRead(Boolean.FALSE);
        userNotificationMapper.insert(entity);
    }

    private UserNotificationResponse toResponse(UserNotificationQueryRow row) {
        return new UserNotificationResponse(
                row.getId(),
                safeText(row.getType()),
                row.getActorUserId(),
                safeFallbackText(row.getActorNickname(), DEFAULT_ACTOR_NAME),
                safeText(row.getActorAvatar()),
                row.getWalkId(),
                safeText(row.getWalkTitle()),
                row.getCommentId(),
                safeText(row.getCommentContent()),
                Boolean.TRUE.equals(row.getIsRead()),
                toEpochMilli(row.getCreatedAt())
        );
    }

    private int normalizePageSize(int pageSize) {
        return Math.min(Math.max(pageSize, 1), 50);
    }

    private int normalizeOffset(int page, int limit) {
        int normalizedPage = Math.max(page, 1);
        return (normalizedPage - 1) * limit;
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String safeFallbackText(String value, String fallback) {
        String normalized = safeText(value);
        return normalized.isBlank() ? fallback : normalized;
    }

    private Long toEpochMilli(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toEpochMilli();
    }
}
