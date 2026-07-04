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
public class SpringAiChatMemoryAdapter implements ChatMemory {

    private final AgentMemoryService agentMemoryService;

    public SpringAiChatMemoryAdapter(AgentMemoryService agentMemoryService) {
        this.agentMemoryService = agentMemoryService;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        Long userId = parseConversationId(conversationId);
        if (userId == null || messages == null || messages.isEmpty()) {
            return;
        }

        String pendingUserPrompt = null;
        for (Message message : messages) {
            if (message instanceof UserMessage userMessage) {
                pendingUserPrompt = normalize(userMessage.getText());
                continue;
            }
            if (message instanceof AssistantMessage assistantMessage && pendingUserPrompt != null) {
                String assistantAnswer = normalize(assistantMessage.getText());
                if (!pendingUserPrompt.isBlank() && !assistantAnswer.isBlank()) {
                    agentMemoryService.appendTurn(userId, pendingUserPrompt, assistantAnswer);
                }
                pendingUserPrompt = null;
            }
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        Long userId = parseConversationId(conversationId);
        if (userId == null) {
            return List.of();
        }

        List<LlmMessage> history = agentMemoryService.loadConversation(userId);
        if (history == null || history.isEmpty()) {
            return List.of();
        }

        List<Message> messages = new ArrayList<>();
        for (LlmMessage item : history) {
            Message message = toSpringAiMessage(item);
            if (message != null) {
                messages.add(message);
            }
        }
        return messages;
    }

    @Override
    public void clear(String conversationId) {
        Long userId = parseConversationId(conversationId);
        if (userId != null) {
            agentMemoryService.clearConversation(userId);
        }
    }

    private Message toSpringAiMessage(LlmMessage item) {
        if (item == null || item.role() == null || item.role().isBlank()) {
            return null;
        }
        return switch (item.role()) {
            case "user" -> new UserMessage(normalize(item.content()));
            case "assistant" -> new AssistantMessage(normalize(item.content()));
            default -> null;
        };
    }

    private Long parseConversationId(String conversationId) {
        String normalized = normalize(conversationId);
        if (normalized.isBlank()) {
            return null;
        }
        try {
            long userId = Long.parseLong(normalized);
            return userId > 0 ? userId : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
