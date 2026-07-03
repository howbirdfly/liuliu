package com.liuliu.citywalk.service.agent;

import org.springframework.ai.tool.ToolCallback;

import java.util.Map;

public interface AgentTool {

    String name();

    String description();

    Map<String, Object> inputSchema();

    @Deprecated
    default Map<String, Object> parametersSchema() {
        return inputSchema();
    }

    String execute(Map<String, Object> arguments);

    default boolean supportsIdempotentReplay() {
        return false;
    }

    default boolean supportsSharedResultCache() {
        return false;
    }

    default LlmToolDefinition toDefinition() {
        return new LlmToolDefinition(name(), description(), inputSchema());
    }

    default ToolCallback toToolCallback() {
        return SpringAiToolCallbackAdapter.fromDefinition(toDefinition());
    }
}
