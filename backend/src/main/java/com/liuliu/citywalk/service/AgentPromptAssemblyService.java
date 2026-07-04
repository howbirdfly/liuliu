package com.liuliu.citywalk.service;

import com.liuliu.citywalk.service.agent.LlmMessage;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class AgentPromptAssemblyService {

    private final ChatMemory chatMemory;
    private final AgentConversationStateService agentConversationStateService;
    private final AgentLongTermMemoryService agentLongTermMemoryService;
    private final AgentContextWindowService agentContextWindowService;

    public AgentPromptAssemblyService(
            ChatMemory chatMemory,
            AgentConversationStateService agentConversationStateService,
            AgentLongTermMemoryService agentLongTermMemoryService,
            AgentContextWindowService agentContextWindowService
    ) {
        this.chatMemory = chatMemory;
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
        if (userId == null || userId <= 0) {
            return List.of();
        }
        return toLlmMessages(chatMemory.get(String.valueOf(userId)));
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
        appendConversationTurn(userId, userPrompt, assistantAnswer);
        agentLongTermMemoryService.rememberTurn(userId, userPrompt, assistantAnswer);
    }

    public void rememberConversationShortTerm(Long userId, String userPrompt, String assistantAnswer) {
        appendConversationTurn(userId, userPrompt, assistantAnswer);
    }

    public void clearConversation(Long userId) {
        if (userId != null && userId > 0) {
            chatMemory.clear(String.valueOf(userId));
        }
        agentConversationStateService.clearState(userId);
    }

    private void appendConversationTurn(Long userId, String userPrompt, String assistantAnswer) {
        if (userId == null || userId <= 0) {
            return;
        }
        chatMemory.add(String.valueOf(userId), List.of(
                new UserMessage(userPrompt == null ? "" : userPrompt.trim()),
                new AssistantMessage(assistantAnswer == null ? "" : assistantAnswer.trim())
        ));
    }

    private List<LlmMessage> toLlmMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<LlmMessage> result = new ArrayList<>();
        for (Message message : messages) {
            if (message instanceof UserMessage userMessage) {
                result.add(LlmMessage.user(userMessage.getText()));
                continue;
            }
            if (message instanceof AssistantMessage assistantMessage) {
                result.add(LlmMessage.assistant(assistantMessage.getText(), assistantMessage.getToolCalls()));
            }
        }
        return result;
    }
}
