package com.liuliu.citywalk.service.agent;

import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

public record LlmResponse(
        String content,
        List<AssistantMessage.ToolCall> toolCalls
) {

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
