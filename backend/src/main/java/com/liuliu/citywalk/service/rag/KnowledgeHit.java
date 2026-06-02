package com.liuliu.citywalk.service.rag;

import java.util.Map;

public record KnowledgeHit(
        String chunkId,
        String sourceId,
        String sourceType,
        String title,
        String content,
        double score,
        Map<String, Object> metadata
) {
}
