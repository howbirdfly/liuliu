package com.liuliu.citywalk.model.dto.response;

public record MissionVerifyResponse(
        boolean passed,
        String comment,
        String confidence,
        Long reviewedAt,
        String reason
) {
}
