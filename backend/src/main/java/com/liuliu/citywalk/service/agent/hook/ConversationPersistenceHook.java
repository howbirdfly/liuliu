package com.liuliu.citywalk.service.agent.hook;

import com.liuliu.citywalk.service.AgentConversationStateService;
import com.liuliu.citywalk.service.AgentPromptAssemblyService;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class ConversationPersistenceHook implements AgentExecutionHook {

    private static final String ATTR_REMEMBER_SHORT_TERM_ONLY = "rememberShortTermOnly";

    private final AgentPromptAssemblyService agentPromptAssemblyService;
    private final AgentConversationStateService agentConversationStateService;

    public ConversationPersistenceHook(
            AgentPromptAssemblyService agentPromptAssemblyService,
            AgentConversationStateService agentConversationStateService
    ) {
        this.agentPromptAssemblyService = agentPromptAssemblyService;
        this.agentConversationStateService = agentConversationStateService;
    }

    @Override
    public Set<AgentExecutionHookPoint> hookPoints() {
        return Set.of(AgentExecutionHookPoint.AFTER_AGENT_LOOP);
    }

    @Override
    public int order() {
        return 100;
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
        boolean rememberShortTermOnly = Boolean.TRUE.equals(context.attributes().get(ATTR_REMEMBER_SHORT_TERM_ONLY));
        if (rememberShortTermOnly) {
            agentPromptAssemblyService.rememberConversationShortTerm(
                    context.userId(),
                    context.normalizedPrompt(),
                    finalAnswer
            );
        } else {
            agentPromptAssemblyService.rememberConversation(
                    context.userId(),
                    context.normalizedPrompt(),
                    finalAnswer
            );
        }
        agentConversationStateService.rememberState(context.userId(), context.conversationState());
        return AgentExecutionHookResult.continueExecution();
    }
}
