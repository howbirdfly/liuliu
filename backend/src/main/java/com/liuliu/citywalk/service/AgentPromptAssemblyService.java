package com.liuliu.citywalk.service;

import com.liuliu.citywalk.service.agent.LlmMessage;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public List<LlmMessage> buildCurrentTurnMessages(
            String userPrompt,
            String carryoverContext,
            String stateMessage
    ) {
        return agentContextWindowService.buildConversationMessages(
                List.of(),
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

    public String buildInstructions(List<InstructionSection> sections) {
        if (sections == null || sections.isEmpty()) {
            return "";
        }

        Map<String, String> orderedSections = new LinkedHashMap<>();
        for (InstructionSection section : sections) {
            if (section == null) {
                continue;
            }
            String normalizedKey = normalizeSectionKey(section.key());
            String normalizedContent = normalizeSectionContent(section.content());
            if (normalizedContent.isBlank()) {
                continue;
            }
            orderedSections.put(normalizedKey, normalizedContent);
        }
        return String.join("\n\n", orderedSections.values());
    }

    public InstructionSection buildLongTermMemorySection(Long userId) {
        return section("long_term_memory", agentLongTermMemoryService.buildPromptContext(userId));
    }

    public static InstructionSection section(String key, String content) {
        return new InstructionSection(key, content);
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

    private String normalizeSectionKey(String key) {
        if (key == null || key.isBlank()) {
            return "section_" + System.identityHashCode(key);
        }
        return key.trim();
    }

    private String normalizeSectionContent(String content) {
        return content == null ? "" : content.trim();
    }

    public record InstructionSection(
            String key,
            String content
    ) {
    }
}
