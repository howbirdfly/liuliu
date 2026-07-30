package com.liuliu.citywalk.service.agent.hook;

public record AgentExecutionHookResult(
        Disposition disposition,
        String finalAnswer,
        String code,
        String stepType,
        String stepName,
        String stepInput,
        boolean rememberShortTermOnly
) {

    public enum Disposition {
        CONTINUE,
        STOP_HOOK_CHAIN,
        COMPLETE_EXECUTION
    }

    public static AgentExecutionHookResult continueExecution() {
        return new AgentExecutionHookResult(Disposition.CONTINUE, null, null, null, null, null, false);
    }

    public static AgentExecutionHookResult stopHookChain() {
        return new AgentExecutionHookResult(Disposition.STOP_HOOK_CHAIN, null, null, null, null, null, false);
    }

    public static AgentExecutionHookResult completeExecution(
            String finalAnswer,
            String code,
            String stepType,
            String stepName,
            String stepInput,
            boolean rememberShortTermOnly
    ) {
        return new AgentExecutionHookResult(
                Disposition.COMPLETE_EXECUTION,
                finalAnswer,
                code,
                stepType,
                stepName,
                stepInput,
                rememberShortTermOnly
        );
    }

    public boolean shouldStopHookChain() {
        return disposition == Disposition.STOP_HOOK_CHAIN || disposition == Disposition.COMPLETE_EXECUTION;
    }

    public boolean shouldCompleteExecution() {
        return disposition == Disposition.COMPLETE_EXECUTION;
    }
}
