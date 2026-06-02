package com.liuliu.citywalk.service.rag;

import java.util.List;
import java.util.Map;

public record KnowledgeDocument(
        String chunkId,
        String sourceId,
        String sourceType,
        String title,
        String content,
        List<Float> embedding,
        Map<String, Object> metadata
) {
}
