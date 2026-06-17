package com.liuliu.citywalk.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(
        prefix = "liuliu.redis.agent-tool-cache",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true
)
public class NoopAgentToolResultCacheService implements AgentToolResultCacheService {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public String get(String invocationSignature) {
        return null;
    }

    @Override
    public void put(String invocationSignature, String output) {
        // No-op fallback when shared cache is disabled.
    }
}
