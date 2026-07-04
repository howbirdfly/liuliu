package com.liuliu.citywalk.service.rag;

import com.liuliu.citywalk.config.RagProperties;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class KnowledgeSearchService {

    private final EmbeddingService embeddingService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final RuleBasedKnowledgeReranker ruleBasedKnowledgeReranker;
    private final SpringAiDocumentMapper springAiDocumentMapper;
    private final RagProperties ragProperties;

    public KnowledgeSearchService(
            EmbeddingService embeddingService,
            KnowledgeRetrievalService knowledgeRetrievalService,
            RuleBasedKnowledgeReranker ruleBasedKnowledgeReranker,
            SpringAiDocumentMapper springAiDocumentMapper,
            RagProperties ragProperties
    ) {
        this.embeddingService = embeddingService;
        this.knowledgeRetrievalService = knowledgeRetrievalService;
        this.ruleBasedKnowledgeReranker = ruleBasedKnowledgeReranker;
        this.springAiDocumentMapper = springAiDocumentMapper;
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
                normalizedQuery,
                embedding,
                retrievalTopK,
                filters == null ? Map.of() : filters
        ));
        if (!ragProperties.isRerankEnabled()) {
            return hits.stream().limit(normalizedTopK).toList();
        }
        return ruleBasedKnowledgeReranker.rerank(normalizedQuery, normalizedTopK, hits);
    }

    public KnowledgeSearchDebugResult debugSearch(String queryText, int topK, Map<String, Object> filters) {
        String normalizedQuery = queryText == null ? "" : queryText.trim();
        if (normalizedQuery.isBlank()) {
            return new KnowledgeSearchDebugResult(List.of(), List.of(), Math.max(1, topK), Math.max(1, topK), ragProperties.isRerankEnabled());
        }
        List<Float> embedding = embeddingService.embed(normalizedQuery);
        if (embedding.isEmpty()) {
            return new KnowledgeSearchDebugResult(List.of(), List.of(), Math.max(1, topK), Math.max(1, topK), ragProperties.isRerankEnabled());
        }

        int normalizedTopK = Math.max(1, topK);
        int retrievalTopK = resolveRetrievalTopK(normalizedTopK);
        List<KnowledgeHit> rawHits = knowledgeRetrievalService.retrieve(new VectorSearchQuery(
                normalizedQuery,
                embedding,
                retrievalTopK,
                filters == null ? Map.of() : filters
        ));
        List<KnowledgeHit> rerankedHits = ragProperties.isRerankEnabled()
                ? ruleBasedKnowledgeReranker.rerank(normalizedQuery, normalizedTopK, rawHits)
                : rawHits.stream().limit(normalizedTopK).toList();
        return new KnowledgeSearchDebugResult(
                rawHits,
                rerankedHits,
                normalizedTopK,
                retrievalTopK,
                ragProperties.isRerankEnabled()
        );
    }

    public List<Document> searchDocuments(String queryText, int topK, Map<String, Object> filters) {
        return search(queryText, topK, filters).stream()
                .map(springAiDocumentMapper::toDocument)
                .filter(document -> document != null)
                .toList();
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

    public record KnowledgeSearchDebugResult(
            List<KnowledgeHit> rawHits,
            List<KnowledgeHit> rerankedHits,
            int topK,
            int candidateTopK,
            boolean rerankEnabled
    ) {
    }
}
