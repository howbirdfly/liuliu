package com.liuliu.citywalk.model.dto.response;

public record AgentStreamEventResponse(
        String type,
        String name,
        String input,
        String output,
        Integer iteration,
        String provider,
        String model
) {
}
