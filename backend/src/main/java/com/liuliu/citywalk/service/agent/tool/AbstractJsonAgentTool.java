package com.liuliu.citywalk.service.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.service.agent.AgentTool;

import java.util.Map;

public abstract class AbstractJsonAgentTool implements AgentTool {

    protected final ObjectMapper objectMapper;

    protected AbstractJsonAgentTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    protected String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("tool_json_encode_failed");
        }
    }

    protected String stringArg(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    protected int intArg(Map<String, Object> arguments, String key, int fallback) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    protected long longArg(Map<String, Object> arguments, String key, long fallback) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    protected double doubleArg(Map<String, Object> arguments, String key, double fallback) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException error) {
            return fallback;
        }
    }
}
