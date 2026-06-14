package com.liuliu.citywalk.service.agent;

import java.util.Map;

public interface AgentTool {

    String name();

    String description();

    Map<String, Object> parametersSchema();

    String execute(Map<String, Object> arguments);

    default boolean supportsIdempotentReplay() {
        return false;
    }

    default LlmToolDefinition toDefinition() {
        return new LlmToolDefinition(name(), description(), parametersSchema());
    }
}
