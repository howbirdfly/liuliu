package com.liuliu.citywalk.model.dto.response;

import java.util.List;

public record AgentChatResponse(
        String answer,
        List<AgentStepResponse> steps,
        Integer iterations,
        String provider,
        String model
) {
}
