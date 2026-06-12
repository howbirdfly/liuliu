package com.liuliu.citywalk.model.dto.response;

public record AgentStreamInitResponse(
        String executionId,
        String streamToken,
        long expiresInSeconds
) {
}
