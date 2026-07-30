package com.liuliu.citywalk.model.dto.response;

public record AgentStreamEventResponse(
        String type,
        String name,
        String input,
        String output,
        Integer iteration,
        String provider,
        String model,
        String code,
        String operationId,
        String phase,
        String message
) {

    public AgentStreamEventResponse(
            String type,
            String name,
            String input,
            String output,
            Integer iteration,
            String provider,
            String model,
            String code
    ) {
        this(type, name, input, output, iteration, provider, model, code, null, null, null);
    }
}
