package com.liuliu.citywalk.service.agent;

import java.util.List;

public record LlmResponse(
        String content,
        List<LlmToolCall> toolCalls,
        String rawResponse
) {

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
