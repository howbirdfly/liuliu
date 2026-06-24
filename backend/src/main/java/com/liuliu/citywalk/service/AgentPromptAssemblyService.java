package com.liuliu.citywalk.service;

import com.liuliu.citywalk.service.agent.LlmMessage;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AgentPromptAssemblyService {

    private final AgentMemoryService agentMemoryService;
    private final AgentConversationStateService agentConversationStateService;
    private final AgentLongTermMemoryService agentLongTermMemoryService;
    private final AgentContextWindowService agentContextWindowService;

    public AgentPromptAssemblyService(
            AgentMemoryService agentMemoryService,
            AgentConversationStateService agentConversationStateService,
            AgentLongTermMemoryService agentLongTermMemoryService,
            AgentContextWindowService agentContextWindowService
    ) {
        this.agentMemoryService = agentMemoryService;
        this.agentConversationStateService = agentConversationStateService;
        this.agentLongTermMemoryService = agentLongTermMemoryService;
        this.agentContextWindowService = agentContextWindowService;
    }

    public List<LlmMessage> buildConversationMessages(Long userId, String userPrompt) {
        return buildConversationMessages(loadConversationHistory(userId), userPrompt, "", "");
    }

    public List<LlmMessage> buildConversationMessages(
            List<LlmMessage> history,
            String userPrompt,
            String carryoverContext
    ) {
        return buildConversationMessages(history, userPrompt, carryoverContext, "");
    }

    public List<LlmMessage> buildConversationMessages(
            List<LlmMessage> history,
            String userPrompt,
            String carryoverContext,
            String stateMessage
    ) {
        return agentContextWindowService.buildConversationMessages(
                history,
                userPrompt,
                carryoverContext,
                stateMessage
        );
    }

    public List<LlmMessage> loadConversationHistory(Long userId) {
        return agentMemoryService.loadConversation(userId);
    }

    public String buildInstructions(
            Long userId,
            String defaultInstructions,
            String runtimeContext,
            String fallbackGuide
    ) {
        String memoryContext = agentLongTermMemoryService.buildPromptContext(userId);
        String normalizedRuntimeContext = runtimeContext == null ? "" : runtimeContext;
        if (memoryContext.isBlank() && normalizedRuntimeContext.isBlank()) {
            return defaultInstructions + fallbackGuide;
        }
        return defaultInstructions + normalizedRuntimeContext + memoryContext + fallbackGuide;
    }

    public void rememberConversation(Long userId, String userPrompt, String assistantAnswer) {
        agentMemoryService.appendTurn(userId, userPrompt, assistantAnswer);
        agentLongTermMemoryService.rememberTurn(userId, userPrompt, assistantAnswer);
    }

    public void rememberConversationShortTerm(Long userId, String userPrompt, String assistantAnswer) {
        agentMemoryService.appendTurn(userId, userPrompt, assistantAnswer);
    }

    public void clearConversation(Long userId) {
        agentMemoryService.clearConversation(userId);
        agentConversationStateService.clearState(userId);
    }
}
