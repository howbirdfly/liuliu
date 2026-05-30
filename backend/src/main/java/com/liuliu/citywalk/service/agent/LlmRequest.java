package com.liuliu.citywalk.service.agent;

import java.util.List;

public record LlmRequest(
        String instructions,
        List<LlmMessage> messages,
        List<LlmToolDefinition> tools,
        Double temperature
) {
}
