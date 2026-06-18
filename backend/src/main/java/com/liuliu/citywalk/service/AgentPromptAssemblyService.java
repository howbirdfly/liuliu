package com.liuliu.citywalk.service;

import com.liuliu.citywalk.service.agent.LlmMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class AgentPromptAssemblyService {

    private final AgentMemoryService agentMemoryService;
    private final AgentLongTermMemoryService agentLongTermMemoryService;

    public AgentPromptAssemblyService(
            AgentMemoryService agentMemoryService,
            AgentLongTermMemoryService agentLongTermMemoryService
    ) {
        this.agentMemoryService = agentMemoryService;
        this.agentLongTermMemoryService = agentLongTermMemoryService;
    }

    public List<LlmMessage> buildConversationMessages(Long userId, String userPrompt) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.addAll(agentMemoryService.loadConversation(userId));
        messages.add(LlmMessage.user(userPrompt));
        return messages;
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
