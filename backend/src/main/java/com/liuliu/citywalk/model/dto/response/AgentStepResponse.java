package com.liuliu.citywalk.model.dto.response;

public record AgentStepResponse(
        String type,
        String name,
        String input,
        String output
) {
}
