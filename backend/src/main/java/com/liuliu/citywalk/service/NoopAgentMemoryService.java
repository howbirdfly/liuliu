package com.liuliu.citywalk.service;

import com.liuliu.citywalk.service.agent.LlmMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@ConditionalOnMissingBean(AgentMemoryService.class)
public class NoopAgentMemoryService implements AgentMemoryService {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public List<LlmMessage> loadConversation(Long userId) {
        return List.of();
    }

    @Override
    public void appendTurn(Long userId, String userPrompt, String assistantAnswer) {
        // no-op
    }

    @Override
    public void clearConversation(Long userId) {
        // no-op
    }
}
