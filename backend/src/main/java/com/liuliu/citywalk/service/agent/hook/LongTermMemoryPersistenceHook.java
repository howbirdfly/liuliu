package com.liuliu.citywalk.service.agent.hook;

import com.liuliu.citywalk.service.AgentLongTermMemoryService;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class LongTermMemoryPersistenceHook implements AgentExecutionHook {

    private final AgentLongTermMemoryService agentLongTermMemoryService;

    public LongTermMemoryPersistenceHook(AgentLongTermMemoryService agentLongTermMemoryService) {
        this.agentLongTermMemoryService = agentLongTermMemoryService;
    }

    @Override
    public Set<AgentExecutionHookPoint> hookPoints() {
        return Set.of(AgentExecutionHookPoint.AFTER_AGENT_LOOP);
    }

    @Override
    public int order() {
        return 110;
    }

    @Override
    public AgentExecutionHookResult handle(AgentExecutionHookContext context) {
        if (context == null || context.userId() == null || context.userId() <= 0) {
            return AgentExecutionHookResult.continueExecution();
        }
        String finalAnswer = context.finalAnswer();
        if (finalAnswer == null || finalAnswer.isBlank()) {
            return AgentExecutionHookResult.continueExecution();
        }
        boolean rememberShortTermOnly = Boolean.TRUE.equals(
                context.attributes().get(AgentExecutionHookAttributes.REMEMBER_SHORT_TERM_ONLY)
        );
        if (rememberShortTermOnly) {
            return AgentExecutionHookResult.continueExecution();
        }
        agentLongTermMemoryService.rememberTurn(
                context.userId(),
                context.normalizedPrompt(),
                finalAnswer
        );
        return AgentExecutionHookResult.continueExecution();
    }
}
