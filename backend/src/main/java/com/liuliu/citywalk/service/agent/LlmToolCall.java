package com.liuliu.citywalk.service.agent;

public record LlmToolCall(
        String id,
        String name,
        String argumentsJson
) {
}
