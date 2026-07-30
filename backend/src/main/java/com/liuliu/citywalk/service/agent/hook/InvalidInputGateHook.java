package com.liuliu.citywalk.service.agent.hook;

import com.liuliu.citywalk.service.AgentExecutionPromptGateService;
import com.liuliu.citywalk.service.AgentIntentAnalysisService;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class InvalidInputGateHook implements AgentExecutionHook {

    private final AgentExecutionPromptGateService agentExecutionPromptGateService;

    public InvalidInputGateHook(AgentExecutionPromptGateService agentExecutionPromptGateService) {
        this.agentExecutionPromptGateService = agentExecutionPromptGateService;
    }

    @Override
    public Set<AgentExecutionHookPoint> hookPoints() {
        return Set.of(AgentExecutionHookPoint.BEFORE_AGENT_LOOP);
    }

    @Override
    public int order() {
        return -200;
    }

    @Override
    public AgentExecutionHookResult handle(AgentExecutionHookContext context) {
        AgentIntentAnalysisService.AgentIntent intent = context.intent();
        if (intent == null || !intent.requiresValidInputPrompt()) {
            return AgentExecutionHookResult.continueExecution();
        }
        return AgentExecutionHookResult.completeExecution(
                agentExecutionPromptGateService.buildValidInputPrompt(intent),
                "valid_input_required",
                "input_gate",
                "valid_input_gate",
                context.normalizedPrompt(),
                true
        );
    }
}
