package com.liuliu.citywalk.service;

import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class AgentStreamAccessService {

    private static final long DEFAULT_TTL_MILLIS = 2L * 60L * 1000L;

    private final ConcurrentMap<String, StreamAccessGrant> grantsByToken = new ConcurrentHashMap<>();

    public StreamTokenGrant issue(Long userId, String executionId) {
        if (userId == null || userId <= 0) {
            throw new IllegalStateException("login_required");
        }
        String normalizedExecutionId = normalizeExecutionId(executionId);
        cleanupExpired();

        String token = UUID.randomUUID().toString();
        long expiresAtMillis = System.currentTimeMillis() + DEFAULT_TTL_MILLIS;
        grantsByToken.put(token, new StreamAccessGrant(userId, normalizedExecutionId, expiresAtMillis));
        return new StreamTokenGrant(token, DEFAULT_TTL_MILLIS / 1000L);
    }

    public Long consumeUserId(String token, String executionId) {
        String normalizedToken = token == null ? "" : token.trim();
        if (normalizedToken.isBlank()) {
            return null;
        }
        StreamAccessGrant grant = grantsByToken.remove(normalizedToken);
        if (grant == null) {
            return null;
        }
        if (grant.expiresAtMillis() < System.currentTimeMillis()) {
            return null;
        }
        if (!Objects.equals(grant.executionId(), normalizeExecutionId(executionId))) {
            return null;
        }
        return grant.userId();
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        grantsByToken.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() < now);
    }

    private String normalizeExecutionId(String executionId) {
        String normalized = executionId == null ? "" : executionId.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("execution_id_required");
        }
        return normalized;
    }

    private record StreamAccessGrant(
            Long userId,
            String executionId,
            long expiresAtMillis
    ) {
    }

    public record StreamTokenGrant(
            String token,
            long expiresInSeconds
    ) {
    }
}
