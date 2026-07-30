package com.liuliu.citywalk.service.agent.hook;

import java.util.Set;

public interface AgentExecutionHook {

    Set<AgentExecutionHookPoint> hookPoints();

    default int order() {
        return 0;
    }

    AgentExecutionHookResult handle(AgentExecutionHookContext context);
}
