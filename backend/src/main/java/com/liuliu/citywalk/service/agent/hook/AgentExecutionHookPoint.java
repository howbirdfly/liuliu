package com.liuliu.citywalk.service.agent.hook;

public enum AgentExecutionHookPoint {
    BEFORE_AGENT_LOOP,
    BEFORE_ROUND,
    BEFORE_LLM_CALL,
    AFTER_LLM_RESPONSE,
    BEFORE_TOOL_CALL,
    AFTER_TOOL_RESULT,
    AFTER_ROUND,
    BEFORE_FINAL_ANSWER,
    AFTER_AGENT_LOOP
}
