package com.liuliu.citywalk.service;

public record AgentExecutionEvent(
        String type,
        String name,
        String input,
        String output,
        int iteration,
        String provider,
        String model,
        String code
) {
}
