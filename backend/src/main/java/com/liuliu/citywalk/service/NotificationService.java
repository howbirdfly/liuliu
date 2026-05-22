package com.liuliu.citywalk.service;

import com.liuliu.citywalk.mapper.CommunityCommentMapper;
import com.liuliu.citywalk.mapper.UserNotificationMapper;
import com.liuliu.citywalk.mapper.WalkRecordMapper;
import com.liuliu.citywalk.mapper.entity.CommunityCommentQueryRow;
import com.liuliu.citywalk.mapper.entity.UserNotificationEntity;
import com.liuliu.citywalk.mapper.entity.UserNotificationQueryRow;
import com.liuliu.citywalk.mapper.entity.WalkRecordEntity;
import com.liuliu.citywalk.model.dto.response.NotificationUnreadCountResponse;
import com.liuliu.citywalk.model.dto.response.NotificationStreamEventResponse;
import com.liuliu.citywalk.model.dto.response.UserNotificationResponse;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class NotificationService {

    public static final String TYPE_POST_COMMENTED = "post_commented";
    public static final String TYPE_COMMENT_REPLIED = "comment_replied";
    public static final String TYPE_POST_LIKED = "post_liked";
    public static final String TYPE_POST_FAVORITED = "post_favorited";
    private static final String DEFAULT_ACTOR_NAME = "Community Walker";
    private static final long SSE_TIMEOUT_MS = 30L * 60L * 1000L;
    private static final long SSE_RETRY_MS = 3000L;
    private static final long HEARTBEAT_INTERVAL_MS = 25_000L;
    private static final int REPLAY_LIMIT = 100;

    private final UserNotificationMapper userNotificationMapper;
    private final WalkRecordMapper walkRecordMapper;
    private final CommunityCommentMapper communityCommentMapper;
    private final NotificationUnreadCountCache notificationUnreadCountCache;
    private final Map<Long, Map<String, SseEmitter>> emittersByUserId = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "notification-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public NotificationService(
            UserNotificationMapper userNotificationMapper,
            WalkRecordMapper walkRecordMapper,
            CommunityCommentMapper communityCommentMapper,
            NotificationUnreadCountCache notificationUnreadCountCache
    ) {
        this.userNotificationMapper = userNotificationMapper;
        this.walkRecordMapper = walkRecordMapper;
        this.communityCommentMapper = communityCommentMapper;
        this.notificationUnreadCountCache = notificationUnreadCountCache;
        this.heartbeatExecutor.scheduleWithFixedDelay(
                this::broadcastHeartbeatSafely,
                HEARTBEAT_INTERVAL_MS,
                HEARTBEAT_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
    }

    @PreDestroy
    public void shutdownHeartbeat() {
        heartbeatExecutor.shutdownNow();
    }

    public SseEmitter subscribe(Long userId, Long lastEventId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        String emitterId = UUID.randomUUID().toString();

        emittersByUserId
                .computeIfAbsent(userId, key -> new ConcurrentHashMap<>())
                .put(emitterId, emitter);

        emitter.onCompletion(() -> removeEmitter(userId, emitterId));
        emitter.onTimeout(() -> {
            removeEmitter(userId, emitterId);
            emitter.complete();
        });
        emitter.onError(error -> removeEmitter(userId, emitterId));

        replayMissedNotifications(userId, lastEventId, emitterId, emitter);

        sendEvent(userId, emitterId, emitter, new NotificationStreamEventResponse(
                "snapshot",
                unreadCountValue(userId),
                null
        ), true, null);

        return emitter;
    }

    public List<UserNotificationResponse> listNotifications(Long userId, int page, int pageSize) {
        int limit = normalizePageSize(pageSize);
        int offset = normalizeOffset(page, limit);
        return userNotificationMapper.findByRecipientUserId(userId, limit, offset).stream()
                .map(this::toResponse)
                .toList();
    }

    public NotificationUnreadCountResponse unreadCount(Long userId) {
        return new NotificationUnreadCountResponse(unreadCountValue(userId));
    }

    public void markRead(Long notificationId, Long userId) {
        int updatedRows = userNotificationMapper.markRead(notificationId, userId);
        if (updatedRows > 0) {
            notificationUnreadCountCache.incrementIfPresent(userId, -updatedRows);
            pushUnreadCount(userId);
        }
    }

    public void markAllRead(Long userId) {
        int updatedRows = userNotificationMapper.markAllRead(userId);
        if (updatedRows > 0) {
            notificationUnreadCountCache.put(userId, 0L);
            pushUnreadCount(userId);
        }
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
        notificationUnreadCountCache.incrementIfPresent(recipientUserId, 1L);
        pushNotification(recipientUserId, entity.getId());
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

    private void pushNotification(Long recipientUserId, Long notificationId) {
        UserNotificationQueryRow row = userNotificationMapper.findById(notificationId);
        if (row == null) {
            return;
        }

        broadcast(recipientUserId, new NotificationStreamEventResponse(
                "notification",
                unreadCountValue(recipientUserId),
                toResponse(row)
        ), notificationId);
    }

    private void pushUnreadCount(Long userId) {
        broadcast(userId, new NotificationStreamEventResponse(
                "unread_count",
                unreadCountValue(userId),
                null
        ), null);
    }

    private Long unreadCountValue(Long userId) {
        Long cachedValue = notificationUnreadCountCache.get(userId);
        if (cachedValue != null) {
            return Math.max(0L, cachedValue);
        }

        Integer count = userNotificationMapper.countUnreadByRecipientUserId(userId);
        long unreadCount = count == null ? 0L : count.longValue();
        notificationUnreadCountCache.put(userId, unreadCount);
        return unreadCount;
    }

    private void broadcast(Long userId, NotificationStreamEventResponse payload, Long eventId) {
        Map<String, SseEmitter> emitters = emittersByUserId.get(userId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        emitters.forEach((emitterId, emitter) -> sendEvent(userId, emitterId, emitter, payload, false, eventId));
    }

    private void replayMissedNotifications(Long userId, Long lastEventId, String emitterId, SseEmitter emitter) {
        if (lastEventId == null || lastEventId <= 0) {
            return;
        }

        List<UserNotificationQueryRow> missedRows = userNotificationMapper.findByRecipientUserIdAfterId(userId, lastEventId, REPLAY_LIMIT);
        for (UserNotificationQueryRow row : missedRows) {
            if (row.getId() == null) {
                continue;
            }
            sendEvent(
                    userId,
                    emitterId,
                    emitter,
                    new NotificationStreamEventResponse("notification", null, toResponse(row)),
                    false,
                    row.getId()
            );
        }
    }

    private void broadcastHeartbeatSafely() {
        try {
            emittersByUserId.forEach((userId, emitters) -> {
                if (emitters == null || emitters.isEmpty()) {
                    return;
                }
                emitters.forEach((emitterId, emitter) -> sendEvent(
                        userId,
                        emitterId,
                        emitter,
                        new NotificationStreamEventResponse("ping", null, null),
                        false,
                        null
                ));
            });
        } catch (Exception ignored) {
            // Heartbeat should never take down the notification service.
        }
    }

    private void sendEvent(
            Long userId,
            String emitterId,
            SseEmitter emitter,
            NotificationStreamEventResponse payload,
            boolean includeReconnectHint,
            Long eventId
    ) {
        try {
            SseEmitter.SseEventBuilder eventBuilder = SseEmitter.event()
                    .data(payload, org.springframework.http.MediaType.APPLICATION_JSON);
            if (eventId != null && eventId > 0) {
                eventBuilder.id(String.valueOf(eventId));
            }
            if (includeReconnectHint) {
                eventBuilder.reconnectTime(SSE_RETRY_MS);
            }
            emitter.send(eventBuilder);
        } catch (Exception error) {
            removeEmitter(userId, emitterId);
            emitter.completeWithError(error);
        }
    }

    private void removeEmitter(Long userId, String emitterId) {
        Map<String, SseEmitter> emitters = emittersByUserId.get(userId);
        if (emitters == null) {
            return;
        }

        emitters.remove(emitterId);
        if (emitters.isEmpty()) {
            emittersByUserId.remove(userId);
        }
    }
}
