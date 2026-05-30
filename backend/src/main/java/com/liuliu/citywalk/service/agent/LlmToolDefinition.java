package com.liuliu.citywalk.service.agent;

import java.util.Map;

public record LlmToolDefinition(
        String name,
        String description,
        Map<String, Object> parametersSchema
) {
}
