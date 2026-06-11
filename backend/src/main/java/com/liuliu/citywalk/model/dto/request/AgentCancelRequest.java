package com.liuliu.citywalk.model.dto.request;

import jakarta.validation.constraints.NotBlank;

public record AgentCancelRequest(
        @NotBlank(message = "execution_id_required")
        String executionId
) {
}
