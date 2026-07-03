package com.liuliu.citywalk.service.agent;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

public record LlmRequest(
        String instructions,
        List<LlmMessage> messages,
        List<LlmToolDefinition> tools,
        List<ToolCallback> toolCallbacks,
        LlmOptions options
) {

    public Double temperature() {
        return options == null ? null : options.temperature();
    }
}
