package com.liuliu.citywalk.model.dto.response;

import java.util.List;
import java.util.Map;

public record RagSearchResponse(
        String query,
        int topK,
        List<RagSearchHitResponse> hits
) {

    public record RagSearchHitResponse(
            String chunkId,
            String sourceId,
            String sourceType,
            String title,
            String content,
            double score,
            Map<String, Object> metadata
    ) {
    }
}
