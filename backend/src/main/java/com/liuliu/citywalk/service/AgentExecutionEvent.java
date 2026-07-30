package com.liuliu.citywalk.service;

public record AgentExecutionEvent(
        String type,
        String name,
        String input,
        String output,
        int iteration,
        String provider,
        String model,
        String code,
        String operationId,
        String phase,
        String message
) {

    public AgentExecutionEvent(
            String type,
            String name,
            String input,
            String output,
            int iteration,
            String provider,
            String model,
            String code
    ) {
        this(type, name, input, output, iteration, provider, model, code, null, null, null);
    }
}
