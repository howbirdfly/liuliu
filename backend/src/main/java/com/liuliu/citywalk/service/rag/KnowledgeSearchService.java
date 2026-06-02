package com.liuliu.citywalk.service.rag;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class KnowledgeSearchService {

    private final EmbeddingService embeddingService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;

    public KnowledgeSearchService(EmbeddingService embeddingService, KnowledgeRetrievalService knowledgeRetrievalService) {
        this.embeddingService = embeddingService;
        this.knowledgeRetrievalService = knowledgeRetrievalService;
    }

    public List<KnowledgeHit> search(String queryText, int topK, Map<String, Object> filters) {
        String normalizedQuery = queryText == null ? "" : queryText.trim();
        if (normalizedQuery.isBlank()) {
            return List.of();
        }
        List<Float> embedding = embeddingService.embed(normalizedQuery);
        if (embedding.isEmpty()) {
            return List.of();
        }
        return knowledgeRetrievalService.retrieve(new VectorSearchQuery(
                embedding,
                Math.max(1, topK),
                filters == null ? Map.of() : filters
        ));
    }

    public boolean isReady() {
        return embeddingService.isConfigured();
    }
}
