package com.liuliu.citywalk.service;

import java.util.List;

public interface AgentConversationStateStore {

    boolean isEnabled();

    ConversationStateSnapshot loadState(Long userId);

    void saveState(Long userId, ConversationStateSnapshot snapshot);

    void clearState(Long userId);

    record ConversationStateSnapshot(
            List<String> cities,
            List<String> areas,
            List<String> styles,
            List<String> objectives,
            String duration,
            String timePreference,
            String mobilityPreference,
            List<String> avoidTags,
            boolean useCurrentLocation,
            Long updatedAt
    ) {
    }
}
