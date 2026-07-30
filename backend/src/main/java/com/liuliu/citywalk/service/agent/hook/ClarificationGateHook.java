package com.liuliu.citywalk.service.agent.hook;

import com.liuliu.citywalk.service.AgentExecutionPromptGateService;
import com.liuliu.citywalk.service.AgentIntentAnalysisService;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class ClarificationGateHook implements AgentExecutionHook {

    private final AgentExecutionPromptGateService agentExecutionPromptGateService;

    public ClarificationGateHook(AgentExecutionPromptGateService agentExecutionPromptGateService) {
        this.agentExecutionPromptGateService = agentExecutionPromptGateService;
    }

    @Override
    public Set<AgentExecutionHookPoint> hookPoints() {
        return Set.of(AgentExecutionHookPoint.BEFORE_AGENT_LOOP);
    }

    @Override
    public int order() {
        return -100;
    }

    @Override
    public AgentExecutionHookResult handle(AgentExecutionHookContext context) {
        AgentIntentAnalysisService.AgentIntent intent = context.intent();
        if (intent == null || !intent.requiresClarification()) {
            return AgentExecutionHookResult.continueExecution();
        }
        return AgentExecutionHookResult.completeExecution(
                agentExecutionPromptGateService.buildClarificationQuestion(intent),
                "clarification_required",
                "clarification",
                "clarification_gate",
                intent.summary(),
                true
        );
    }
}
