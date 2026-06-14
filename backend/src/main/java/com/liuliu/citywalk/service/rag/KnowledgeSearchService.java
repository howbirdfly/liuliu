package com.liuliu.citywalk.service.rag;

import com.liuliu.citywalk.config.RagProperties;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class KnowledgeSearchService {

    private final EmbeddingService embeddingService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final RuleBasedKnowledgeReranker ruleBasedKnowledgeReranker;
    private final RagProperties ragProperties;

    public KnowledgeSearchService(
            EmbeddingService embeddingService,
            KnowledgeRetrievalService knowledgeRetrievalService,
            RuleBasedKnowledgeReranker ruleBasedKnowledgeReranker,
            RagProperties ragProperties
    ) {
        this.embeddingService = embeddingService;
        this.knowledgeRetrievalService = knowledgeRetrievalService;
        this.ruleBasedKnowledgeReranker = ruleBasedKnowledgeReranker;
        this.ragProperties = ragProperties;
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

        int normalizedTopK = Math.max(1, topK);
        int retrievalTopK = resolveRetrievalTopK(normalizedTopK);
        List<KnowledgeHit> hits = knowledgeRetrievalService.retrieve(new VectorSearchQuery(
                embedding,
                retrievalTopK,
                filters == null ? Map.of() : filters
        ));
        if (!ragProperties.isRerankEnabled()) {
            return hits.stream().limit(normalizedTopK).toList();
        }
        return ruleBasedKnowledgeReranker.rerank(normalizedQuery, normalizedTopK, hits);
    }

    public boolean isReady() {
        return embeddingService.isConfigured();
    }

    private int resolveRetrievalTopK(int topK) {
        if (!ragProperties.isRerankEnabled()) {
            return topK;
        }
        int multiplier = Math.max(1, ragProperties.getRerankCandidateMultiplier());
        int maxTopK = Math.max(topK, ragProperties.getRerankCandidateMaxTopK());
        long candidateTopK = (long) topK * (long) multiplier;
        return (int) Math.min(candidateTopK, maxTopK);
    }
}
