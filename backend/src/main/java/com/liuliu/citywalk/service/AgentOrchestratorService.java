package com.liuliu.citywalk.service;

import com.liuliu.citywalk.model.dto.response.AgentChatResponse;
import org.springframework.stereotype.Service;

@Service
public class AgentOrchestratorService {

    private final AgentExecutionPipelineService agentExecutionPipelineService;
    private final AgentPromptAssemblyService agentPromptAssemblyService;

    public AgentOrchestratorService(
            AgentExecutionPipelineService agentExecutionPipelineService,
            AgentPromptAssemblyService agentPromptAssemblyService
    ) {
        this.agentExecutionPipelineService = agentExecutionPipelineService;
        this.agentPromptAssemblyService = agentPromptAssemblyService;
    }

    public AgentChatResponse chat(Long userId, String prompt) {
        return agentExecutionPipelineService.execute(userId, prompt, null, null);
    }

    public AgentChatResponse stream(
            Long userId,
            String prompt,
            AgentExecutionRegistryService.AgentExecutionHandle executionHandle,
            AgentExecutionListener listener
    ) {
        return agentExecutionPipelineService.execute(userId, prompt, executionHandle, listener);
    }

    public void clearConversation(Long userId) {
        agentPromptAssemblyService.clearConversation(userId);
    }
}
