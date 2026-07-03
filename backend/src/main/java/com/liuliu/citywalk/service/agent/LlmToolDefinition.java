package com.liuliu.citywalk.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public record LlmToolDefinition(
        String name,
        String description,
        Map<String, Object> parametersSchema
) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public Map<String, Object> inputSchema() {
        return parametersSchema;
    }

    public String inputSchemaJson() {
        try {
            return OBJECT_MAPPER.writeValueAsString(inputSchema() == null ? Map.of() : inputSchema());
        } catch (JsonProcessingException error) {
            return "{}";
        }
    }
}
