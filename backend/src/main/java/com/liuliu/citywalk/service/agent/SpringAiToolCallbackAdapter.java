package com.liuliu.citywalk.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Map;

public final class SpringAiToolCallbackAdapter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private SpringAiToolCallbackAdapter() {
    }

    public static ToolCallback fromAgentTool(AgentTool tool) {
        return new DefinitionOnlyToolCallback(tool);
    }

    private static final class DefinitionOnlyToolCallback implements ToolCallback {

        private final ToolDefinition toolDefinition;
        private final ToolMetadata toolMetadata;

        private DefinitionOnlyToolCallback(AgentTool tool) {
            this.toolDefinition = ToolDefinition.builder()
                    .name(tool.name())
                    .description(tool.description())
                    .inputSchema(toJson(tool.inputSchema()))
                    .build();
            this.toolMetadata = ToolMetadata.builder().build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return toolDefinition;
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return toolMetadata;
        }

        @Override
        public String call(String toolInput) {
            throw new UnsupportedOperationException("tool_execution_managed_by_agent_orchestrator");
        }

        private String toJson(Map<String, Object> inputSchema) {
            try {
                return OBJECT_MAPPER.writeValueAsString(inputSchema == null ? Map.of() : inputSchema);
            } catch (Exception error) {
                return "{}";
            }
        }
    }
}
