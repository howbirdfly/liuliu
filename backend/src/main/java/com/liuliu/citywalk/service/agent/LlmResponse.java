package com.liuliu.citywalk.service.agent;

import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

public record LlmResponse(
        String content,
        List<AssistantMessage.ToolCall> toolCalls,
        String finishReason
) {

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public boolean isMaxTokensStop() {
        if (finishReason == null || finishReason.isBlank()) {
            return false;
        }
        String normalized = finishReason.trim().toLowerCase();
        return "max_tokens".equals(normalized)
                || "length".equals(normalized)
                || "max_output_tokens".equals(normalized);
    }
}
