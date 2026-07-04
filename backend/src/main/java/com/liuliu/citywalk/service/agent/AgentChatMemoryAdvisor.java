package com.liuliu.citywalk.service.agent;

import com.liuliu.citywalk.service.AgentContextWindowService;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AgentChatMemoryAdvisor implements BaseAdvisor {

    private final ChatMemory chatMemory;
    private final AgentContextWindowService agentContextWindowService;

    public AgentChatMemoryAdvisor(ChatMemory chatMemory, AgentContextWindowService agentContextWindowService) {
        this.chatMemory = chatMemory;
        this.agentContextWindowService = agentContextWindowService;
    }

    @Override
    public int getOrder() {
        return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;
    }

    @Override
    public String getName() {
        return "agentChatMemoryAdvisor";
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        if (chatClientRequest == null || chatClientRequest.prompt() == null) {
            return chatClientRequest;
        }

        String conversationId = resolveConversationId(chatClientRequest.context());
        if (conversationId.isBlank()) {
            return chatClientRequest;
        }

        List<Message> memoryMessages = chatMemory.get(conversationId);
        if (memoryMessages == null || memoryMessages.isEmpty()) {
            return chatClientRequest;
        }

        List<Message> compactedMemoryMessages = toSpringMessages(
                agentContextWindowService.buildConversationMessages(toLlmMessages(memoryMessages), "", "", "")
        );
        if (compactedMemoryMessages.isEmpty()) {
            return chatClientRequest;
        }

        Prompt prompt = chatClientRequest.prompt();
        List<Message> mergedMessages = new ArrayList<>(compactedMemoryMessages.size() + prompt.getInstructions().size());
        mergedMessages.addAll(compactedMemoryMessages);
        mergedMessages.addAll(prompt.getInstructions());

        return chatClientRequest.mutate()
                .prompt(new Prompt(mergedMessages, prompt.getOptions()))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }

    private String resolveConversationId(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return "";
        }
        Object value = context.get(ChatMemory.CONVERSATION_ID);
        if (value == null) {
            return "";
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() ? "" : normalized;
    }

    private List<LlmMessage> toLlmMessages(List<Message> messages) {
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

    private List<Message> toSpringMessages(List<LlmMessage> messages) {
        List<Message> result = new ArrayList<>();
        for (LlmMessage message : messages) {
            if (message == null || message.role() == null || message.role().isBlank()) {
                continue;
            }
            switch (message.role()) {
                case "system" -> result.add(new org.springframework.ai.chat.messages.SystemMessage(message.content()));
                case "user" -> result.add(new UserMessage(message.content()));
                case "assistant" -> result.add(AssistantMessage.builder()
                        .content(message.content())
                        .toolCalls(message.toolCalls() == null ? List.of() : message.toolCalls())
                        .build());
                default -> {
                }
            }
        }
        return result;
    }
}
