package com.liuliu.citywalk.service;

import com.liuliu.citywalk.service.agent.LlmMessage;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AgentPromptAssemblyService {

    private final AgentMemoryService agentMemoryService;
    private final AgentLongTermMemoryService agentLongTermMemoryService;
    private final AgentContextWindowService agentContextWindowService;

    public AgentPromptAssemblyService(
            AgentMemoryService agentMemoryService,
            AgentLongTermMemoryService agentLongTermMemoryService,
            AgentContextWindowService agentContextWindowService
    ) {
        this.agentMemoryService = agentMemoryService;
        this.agentLongTermMemoryService = agentLongTermMemoryService;
        this.agentContextWindowService = agentContextWindowService;
    }

    public List<LlmMessage> buildConversationMessages(Long userId, String userPrompt) {
        return agentContextWindowService.buildConversationMessages(
                agentMemoryService.loadConversation(userId),
                userPrompt
        );
    }

    public String buildInstructions(Long userId, String defaultInstructions, String fallbackGuide) {
        String memoryContext = agentLongTermMemoryService.buildPromptContext(userId);
        if (memoryContext.isBlank()) {
            return defaultInstructions + fallbackGuide;
        }
        return defaultInstructions + memoryContext + fallbackGuide;
    }

    public void rememberConversation(Long userId, String userPrompt, String assistantAnswer) {
        agentMemoryService.appendTurn(userId, userPrompt, assistantAnswer);
        agentLongTermMemoryService.rememberTurn(userId, userPrompt, assistantAnswer);
    }

    public void clearConversation(Long userId) {
        agentMemoryService.clearConversation(userId);
    }
}