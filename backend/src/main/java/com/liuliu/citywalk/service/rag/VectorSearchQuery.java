package com.liuliu.citywalk.service.rag;

import java.util.List;
import java.util.Map;

public record VectorSearchQuery(
        String queryText,
        List<Float> embedding,
        int topK,
        Map<String, Object> filters
) {
}
