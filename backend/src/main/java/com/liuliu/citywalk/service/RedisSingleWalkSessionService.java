package com.liuliu.citywalk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.config.SingleWalkSessionProperties;
import com.liuliu.citywalk.model.dto.request.PathPointRequest;
import com.liuliu.citywalk.model.dto.request.SingleWalkSessionRequest;
import com.liuliu.citywalk.model.dto.request.WalkSessionLocationRequest;
import com.liuliu.citywalk.model.dto.request.WalkSessionThemeRequest;
import com.liuliu.citywalk.model.dto.response.SingleWalkSessionResponse;
import com.liuliu.citywalk.model.dto.response.WalkSessionLocationResponse;
import com.liuliu.citywalk.model.dto.response.WalkThemeSnapshotResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
@ConditionalOnProperty(prefix = "liuliu.redis.single-walk-session", name = "enabled", havingValue = "true")
public class RedisSingleWalkSessionService implements SingleWalkSessionService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final SingleWalkSessionProperties properties;

    public RedisSingleWalkSessionService(
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper,
            SingleWalkSessionProperties properties
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public SingleWalkSessionResponse loadSession(Long userId) {
        if (userId == null || userId <= 0) {
            return null;
        }
        String value = stringRedisTemplate.opsForValue().get(buildKey(userId));
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            SessionValue session = objectMapper.readValue(value, SessionValue.class);
            return new SingleWalkSessionResponse(
                    normalize(session.walkMode()),
                    toThemeResponse(session.theme()),
                    normalize(session.noteText()),
                    normalizeMissionList(session.checkedMissions()),
                    toLocationResponse(session.selectedLocation()),
                    normalizePath(session.path()),
                    Boolean.TRUE.equals(session.isTracking()),
                    normalize(session.locationContext()),
                    normalize(session.searchLocation()),
                    session.updatedAt()
            );
        } catch (Exception error) {
            return null;
        }
    }

    @Override
    public void saveSession(Long userId, SingleWalkSessionRequest request) {
        if (userId == null || userId <= 0 || request == null) {
            return;
        }
        SessionValue value = new SessionValue(
                normalize(request.walkMode()),
                toThemeValue(request.theme()),
                normalize(request.noteText()),
                normalizeMissionList(request.checkedMissions()),
                toLocationValue(request.selectedLocation()),
                normalizePath(request.path()),
                Boolean.TRUE.equals(request.isTracking()),
                normalize(request.locationContext()),
                normalize(request.searchLocation()),
                System.currentTimeMillis()
        );
        try {
            stringRedisTemplate.opsForValue().set(
                    buildKey(userId),
                    objectMapper.writeValueAsString(value),
                    Duration.ofSeconds(Math.max(300L, properties.getTtlSeconds()))
            );
        } catch (Exception ignored) {
        }
    }

    @Override
    public void clearSession(Long userId) {
        if (userId == null || userId <= 0) {
            return;
        }
        stringRedisTemplate.delete(buildKey(userId));
    }

    private String buildKey(Long userId) {
        return properties.getKeyPrefix() + "user:" + userId;
    }

    private WalkThemeSnapshotResponse toThemeResponse(SessionThemeValue theme) {
        if (theme == null) {
            return null;
        }
        return new WalkThemeSnapshotResponse(
                normalize(theme.title()),
                normalize(theme.description()),
                normalize(theme.category()),
                normalizeMissionList(theme.missions()),
                normalize(theme.vibeColor()),
                normalize(theme.provider()),
                normalize(theme.coverImageUrl())
        );
    }

    private SessionThemeValue toThemeValue(WalkSessionThemeRequest theme) {
        if (theme == null) {
            return null;
        }
        return new SessionThemeValue(
                normalize(theme.title()),
                normalize(theme.description()),
                normalize(theme.category()),
                normalizeMissionList(theme.missions()),
                normalize(theme.vibeColor()),
                normalize(theme.provider()),
                normalize(theme.coverImageUrl())
        );
    }

    private WalkSessionLocationResponse toLocationResponse(SessionLocationValue location) {
        if (location == null) {
            return null;
        }
        return new WalkSessionLocationResponse(
                normalize(location.name()),
                location.lat(),
                location.lng()
        );
    }

    private SessionLocationValue toLocationValue(WalkSessionLocationRequest location) {
        if (location == null) {
            return null;
        }
        return new SessionLocationValue(
                normalize(location.name()),
                location.lat(),
                location.lng()
        );
    }

    private List<String> normalizeMissionList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private List<PathPointRequest> normalizePath(List<PathPointRequest> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(item -> item != null && item.lat() != null && item.lng() != null && item.timestamp() != null)
                .toList();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private record SessionValue(
            String walkMode,
            SessionThemeValue theme,
            String noteText,
            List<String> checkedMissions,
            SessionLocationValue selectedLocation,
            List<PathPointRequest> path,
            Boolean isTracking,
            String locationContext,
            String searchLocation,
            Long updatedAt
    ) {
    }

    private record SessionThemeValue(
            String title,
            String description,
            String category,
            List<String> missions,
            String vibeColor,
            String provider,
            String coverImageUrl
    ) {
    }

    private record SessionLocationValue(
            String name,
            Double lat,
            Double lng
    ) {
    }
}
