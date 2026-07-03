package com.liuliu.citywalk.service.agent;

import java.util.List;

public record LlmResponse(
        String content,
        List<LlmToolCall> toolCalls,
        String rawResponse,
        LlmResponseMetadata metadata
) {

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
