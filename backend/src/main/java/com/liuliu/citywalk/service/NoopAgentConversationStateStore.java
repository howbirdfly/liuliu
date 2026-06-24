package com.liuliu.citywalk.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(
        prefix = "liuliu.redis.agent-memory",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true
)
public class NoopAgentConversationStateStore implements AgentConversationStateStore {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public ConversationStateSnapshot loadState(Long userId) {
        return null;
    }

    @Override
    public void saveState(Long userId, ConversationStateSnapshot snapshot) {
        // no-op
    }

    @Override
    public void clearState(Long userId) {
        // no-op
    }
}
