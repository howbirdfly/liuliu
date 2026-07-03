package com.liuliu.citywalk.service.agent;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

public final class SpringAiToolCallbackAdapter {

    private SpringAiToolCallbackAdapter() {
    }

    public static ToolCallback fromDefinition(LlmToolDefinition definition) {
        return new DefinitionOnlyToolCallback(definition);
    }

    private static final class DefinitionOnlyToolCallback implements ToolCallback {

        private final ToolDefinition toolDefinition;
        private final ToolMetadata toolMetadata;

        private DefinitionOnlyToolCallback(LlmToolDefinition definition) {
            this.toolDefinition = ToolDefinition.builder()
                    .name(definition.name())
                    .description(definition.description())
                    .inputSchema(definition.inputSchemaJson())
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
    }
}
