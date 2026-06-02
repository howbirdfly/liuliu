package com.liuliu.citywalk.model.dto.response;

import java.util.List;

public record RagHealthResponse(
        String vectorProvider,
        boolean vectorEnabled,
        boolean vectorHealthy,
        List<String> vectorReasons,
        String embeddingProvider,
        boolean embeddingConfigured
) {
}
