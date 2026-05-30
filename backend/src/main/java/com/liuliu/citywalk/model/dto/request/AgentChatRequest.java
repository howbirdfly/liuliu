package com.liuliu.citywalk.model.dto.request;

import jakarta.validation.constraints.NotBlank;

public record AgentChatRequest(
        @NotBlank(message = "prompt_required")
        String prompt
) {
}
