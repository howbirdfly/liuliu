package com.liuliu.citywalk.service.agent.hook;

import com.liuliu.citywalk.service.AgentTodoListService;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class TodoListLifecycleHook implements AgentExecutionHook {

    private final AgentTodoListService agentTodoListService;

    public TodoListLifecycleHook(AgentTodoListService agentTodoListService) {
        this.agentTodoListService = agentTodoListService;
    }

    @Override
    public Set<AgentExecutionHookPoint> hookPoints() {
        return Set.of(
                AgentExecutionHookPoint.BEFORE_AGENT_LOOP,
                AgentExecutionHookPoint.AFTER_AGENT_LOOP
        );
    }

    @Override
    public int order() {
        return 5;
    }

    @Override
    public AgentExecutionHookResult handle(AgentExecutionHookContext context) {
        if (context == null) {
            return AgentExecutionHookResult.continueExecution();
        }
        if (context.point() == AgentExecutionHookPoint.BEFORE_AGENT_LOOP) {
            agentTodoListService.openExecutionScope(context.executionId());
            return AgentExecutionHookResult.continueExecution();
        }
        if (context.point() == AgentExecutionHookPoint.AFTER_AGENT_LOOP) {
            agentTodoListService.closeExecutionScope();
        }
        return AgentExecutionHookResult.continueExecution();
    }
}
