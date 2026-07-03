package com.liuliu.citywalk.service.agent;

public record LlmResponseMetadata(
        String provider,
        String model,
        boolean streaming,
        String rawMetadata
) {
}
