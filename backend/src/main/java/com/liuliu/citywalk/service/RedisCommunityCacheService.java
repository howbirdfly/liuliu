package com.liuliu.citywalk.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.config.CommunityCacheProperties;
import com.liuliu.citywalk.mapper.WalkInteractionMapper;
import com.liuliu.citywalk.model.dto.response.CommunityCommentResponse;
import com.liuliu.citywalk.model.dto.response.CommunityWalkResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@ConditionalOnProperty(prefix = "liuliu.redis.community-cache", name = "enabled", havingValue = "true")
public class RedisCommunityCacheService implements CommunityCacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisCommunityCacheService.class);
    private static final TypeReference<List<CommunityWalkResponse>> FEED_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<CommunityCommentResponse>> COMMENT_TYPE = new TypeReference<>() { };

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final CommunityCacheProperties properties;
    private final WalkInteractionMapper walkInteractionMapper;

    public RedisCommunityCacheService(
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper,
            CommunityCacheProperties properties,
            WalkInteractionMapper walkInteractionMapper
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.walkInteractionMapper = walkInteractionMapper;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public List<CommunityWalkResponse> getFeed(String feedType, int page, int pageSize) {
        return readValue(buildFeedKey(feedType, page, pageSize), FEED_TYPE);
    }

    @Override
    public void putFeed(String feedType, int page, int pageSize, List<CommunityWalkResponse> value) {
        writeValue(buildFeedKey(feedType, page, pageSize), value, properties.getFeedTtlSeconds());
    }

    @Override
    public void evictAllFeeds() {
        deleteKeysByPattern(properties.getKeyPrefix() + "feed:*");
    }

    @Override
    public CommunityWalkResponse getWalkDetail(Long walkId) {
        return readValue(buildDetailKey(walkId), CommunityWalkResponse.class);
    }

    @Override
    public void putWalkDetail(Long walkId, CommunityWalkResponse value) {
        writeValue(buildDetailKey(walkId), value, properties.getDetailTtlSeconds());
    }

    @Override
    public void evictWalkDetail(Long walkId) {
        deleteKey(buildDetailKey(walkId));
    }

    @Override
    public List<CommunityCommentResponse> getComments(Long walkId) {
        return readValue(buildCommentKey(walkId), COMMENT_TYPE);
    }

    @Override
    public void putComments(Long walkId, List<CommunityCommentResponse> value) {
        writeValue(buildCommentKey(walkId), value, properties.getCommentTtlSeconds());
    }

    @Override
    public void evictComments(Long walkId) {
        deleteKey(buildCommentKey(walkId));
    }

    @Override
    public long incrementBufferedViewCount(Long walkId) {
        if (!isValidWalkId(walkId)) {
            return 0L;
        }

        String key = buildViewBufferKey(walkId);
        try {
            Long nextValue = stringRedisTemplate.opsForValue().increment(key, 1L);
            stringRedisTemplate.expire(key, Duration.ofSeconds(Math.max(60L, properties.getViewBufferTtlSeconds())));
            return nextValue == null ? 0L : Math.max(0L, nextValue);
        } catch (Exception error) {
            log.warn("Increment community view buffer failed, walkId={}", walkId, error);
            return 0L;
        }
    }

    @Override
    public long getBufferedViewCount(Long walkId) {
        if (!isValidWalkId(walkId)) {
            return 0L;
        }

        try {
            String value = stringRedisTemplate.opsForValue().get(buildViewBufferKey(walkId));
            if (value == null || value.isBlank()) {
                return 0L;
            }
            return Math.max(0L, Long.parseLong(value.trim()));
        } catch (Exception error) {
            log.warn("Read community view buffer failed, walkId={}", walkId, error);
            return 0L;
        }
    }

    @Scheduled(fixedDelayString = "${liuliu.redis.community-cache.view-flush-interval-ms:15000}")
    public void flushBufferedViewCounts() {
        Set<String> keys = scanKeys(properties.getKeyPrefix() + "views:*");
        if (keys.isEmpty()) {
            return;
        }

        for (String key : keys) {
            Long walkId = parseWalkIdFromViewKey(key);
            if (!isValidWalkId(walkId)) {
                deleteKey(key);
                continue;
            }

            try {
                String value = stringRedisTemplate.opsForValue().getAndDelete(key);
                if (value == null || value.isBlank()) {
                    continue;
                }

                long delta = Long.parseLong(value.trim());
                if (delta <= 0L) {
                    continue;
                }
                walkInteractionMapper.incrementViewCountByDelta(walkId, delta);
            } catch (Exception error) {
                log.warn("Flush community view buffer failed, key={}", key, error);
            }
        }
    }

    private String buildFeedKey(String feedType, int page, int pageSize) {
        return properties.getKeyPrefix() + "feed:" + feedType + ":" + page + ":" + pageSize;
    }

    private String buildDetailKey(Long walkId) {
        return properties.getKeyPrefix() + "detail:" + walkId;
    }

    private String buildCommentKey(Long walkId) {
        return properties.getKeyPrefix() + "comments:" + walkId;
    }

    private String buildViewBufferKey(Long walkId) {
        return properties.getKeyPrefix() + "views:" + walkId;
    }

    private boolean isValidWalkId(Long walkId) {
        return walkId != null && walkId > 0L;
    }

    private Long parseWalkIdFromViewKey(String key) {
        int lastIndex = key == null ? -1 : key.lastIndexOf(':');
        if (lastIndex < 0 || lastIndex >= key.length() - 1) {
            return null;
        }
        try {
            return Long.parseLong(key.substring(lastIndex + 1));
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private void deleteKey(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception error) {
            log.warn("Delete community cache key failed, key={}", key, error);
        }
    }

    private void deleteKeysByPattern(String pattern) {
        Set<String> keys = scanKeys(pattern);
        if (keys.isEmpty()) {
            return;
        }
        try {
            stringRedisTemplate.delete(keys);
        } catch (Exception error) {
            log.warn("Delete community cache keys failed, pattern={}", pattern, error);
        }
    }

    private Set<String> scanKeys(String pattern) {
        try {
            return stringRedisTemplate.execute((RedisConnection connection) -> {
                Set<String> keys = new LinkedHashSet<>();
                try (var cursor = connection.scan(org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(pattern)
                        .count(200)
                        .build())) {
                    while (cursor.hasNext()) {
                        byte[] key = cursor.next();
                        if (key != null && key.length > 0) {
                            keys.add(new String(key, StandardCharsets.UTF_8));
                        }
                    }
                } catch (Exception error) {
                    log.warn("Scan community cache keys failed, pattern={}", pattern, error);
                }
                return keys;
            });
        } catch (Exception error) {
            log.warn("Execute community cache scan failed, pattern={}", pattern, error);
            return Set.of();
        }
    }

    private <T> T readValue(String key, Class<T> type) {
        try {
            String value = stringRedisTemplate.opsForValue().get(key);
            if (value == null || value.isBlank()) {
                return null;
            }
            return objectMapper.readValue(value, type);
        } catch (Exception error) {
            log.warn("Read community cache failed, key={}", key, error);
            return null;
        }
    }

    private <T> T readValue(String key, TypeReference<T> typeReference) {
        try {
            String value = stringRedisTemplate.opsForValue().get(key);
            if (value == null || value.isBlank()) {
                return null;
            }
            return objectMapper.readValue(value, typeReference);
        } catch (Exception error) {
            log.warn("Read community cache failed, key={}", key, error);
            return null;
        }
    }

    private void writeValue(String key, Object value, long ttlSeconds) {
        try {
            String json = objectMapper.writeValueAsString(value);
            stringRedisTemplate.opsForValue().set(
                    key,
                    json,
                    Duration.ofSeconds(Math.max(30L, ttlSeconds))
            );
        } catch (Exception error) {
            log.warn("Write community cache failed, key={}", key, error);
        }
    }
}
