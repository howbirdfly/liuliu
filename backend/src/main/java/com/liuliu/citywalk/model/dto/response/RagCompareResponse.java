package com.liuliu.citywalk.model.dto.response;

import java.util.List;
import java.util.Map;

public record RagCompareResponse(
        String query,
        int topK,
        int candidateTopK,
        boolean rerankEnabled,
        List<RagCompareHitResponse> rawHits,
        List<RagCompareHitResponse> rerankedHits
) {

    public record RagCompareHitResponse(
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
