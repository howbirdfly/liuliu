package com.liuliu.citywalk.service.agent;

public class AgentExecutionCancelledException extends RuntimeException {

    public AgentExecutionCancelledException(String message) {
        super(message);
    }

    public AgentExecutionCancelledException(String message, Throwable cause) {
        super(message, cause);
    }
}
