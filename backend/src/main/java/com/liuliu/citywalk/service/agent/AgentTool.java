package com.liuliu.citywalk.service.agent;

import java.util.Map;

import org.springframework.ai.tool.ToolCallback;

public interface AgentTool {

    String name();

    String description();

    Map<String, Object> inputSchema();

    String execute(Map<String, Object> arguments);

    default boolean supportsIdempotentReplay() {
        return false;
    }

    default boolean supportsSharedResultCache() {
        return false;
    }

    default ToolCallback toToolCallback() {
        return SpringAiToolCallbackAdapter.fromAgentTool(this);
    }
}
