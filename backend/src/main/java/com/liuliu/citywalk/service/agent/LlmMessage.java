package com.liuliu.citywalk.service.agent;

import java.util.List;

public record LlmMessage(
        String role,
        String content,
        String toolCallId,
        String name,
        List<LlmToolCall> toolCalls
) {

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content, null, null, List.of());
    }

    public static LlmMessage assistant(String content, List<LlmToolCall> toolCalls) {
        return new LlmMessage("assistant", content, null, null, toolCalls == null ? List.of() : toolCalls);
    }

    public static LlmMessage tool(String toolCallId, String name, String content) {
        return new LlmMessage("tool", content, toolCallId, name, List.of());
    }
}
