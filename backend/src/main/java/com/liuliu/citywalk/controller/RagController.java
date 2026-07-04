package com.liuliu.citywalk.controller;

import com.liuliu.citywalk.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.liuliu.citywalk.context.BaseContext;
import com.liuliu.citywalk.model.dto.response.RagCompareResponse;
import com.liuliu.citywalk.model.dto.response.RagHealthResponse;
import com.liuliu.citywalk.model.dto.response.RagIngestionResponse;
import com.liuliu.citywalk.model.dto.response.RagSearchResponse;
import org.springframework.ai.document.Document;
import com.liuliu.citywalk.service.rag.CommunityKnowledgeIngestionResult;
import com.liuliu.citywalk.service.rag.CommunityKnowledgeIngestionService;
import com.liuliu.citywalk.service.rag.EmbeddingService;
import com.liuliu.citywalk.service.rag.KnowledgeHit;
import com.liuliu.citywalk.service.rag.KnowledgeSearchService;
import com.liuliu.citywalk.service.rag.SpringAiKnowledgeDocumentService;
import com.liuliu.citywalk.service.rag.VectorStore;
import com.liuliu.citywalk.service.rag.VectorStoreHealth;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rag")
@ConditionalOnProperty(prefix = "liuliu.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagController {

    private final VectorStore vectorStore;
    private final EmbeddingService embeddingService;
    private final CommunityKnowledgeIngestionService communityKnowledgeIngestionService;
    private final KnowledgeSearchService knowledgeSearchService;
    private final SpringAiKnowledgeDocumentService springAiKnowledgeDocumentService;

    public RagController(
            VectorStore vectorStore,
            EmbeddingService embeddingService,
            CommunityKnowledgeIngestionService communityKnowledgeIngestionService,
            KnowledgeSearchService knowledgeSearchService,
            SpringAiKnowledgeDocumentService springAiKnowledgeDocumentService
    ) {
        this.vectorStore = vectorStore;
        this.embeddingService = embeddingService;
        this.communityKnowledgeIngestionService = communityKnowledgeIngestionService;
        this.knowledgeSearchService = knowledgeSearchService;
        this.springAiKnowledgeDocumentService = springAiKnowledgeDocumentService;
    }

    @GetMapping("/health")
    public ApiResponse<RagHealthResponse> health() {
        BaseContext.requireCurrentUserId();
        VectorStoreHealth health = vectorStore.health();
        return ApiResponse.success(new RagHealthResponse(
                health.provider(),
                health.enabled(),
                health.reachable(),
                health.reasons(),
                embeddingService.provider(),
                embeddingService.isConfigured()
        ));
    }

    @PostMapping("/ingest/community")
    public ApiResponse<RagIngestionResponse> ingestCommunity(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        try {
            BaseContext.requireCurrentUserId();
            CommunityKnowledgeIngestionResult result = communityKnowledgeIngestionService.ingestLatestPublicWalks(limit, offset);
            return ApiResponse.success(new RagIngestionResponse(
                    result.walkCount(),
                    result.chunkCount(),
                    result.walkIds()
            ));
        } catch (Exception error) {
            return ApiResponse.fail(500, extractErrorMessage(error));
        }
    }

    @GetMapping("/search")
    public ApiResponse<RagSearchResponse> search(
            @RequestParam("query") String query,
            @RequestParam(defaultValue = "5") int topK,
            @RequestParam(required = false) String sourceType
    ) {
        BaseContext.requireCurrentUserId();
        java.util.Map<String, Object> filters = new java.util.LinkedHashMap<>();
        if (sourceType != null && !sourceType.isBlank()) {
            filters.put("source_type", sourceType.trim());
        }
        java.util.List<Document> hits = springAiKnowledgeDocumentService.search(query, topK, filters);
        return ApiResponse.success(new RagSearchResponse(
                query,
                Math.max(1, topK),
                hits.stream()
                        .map(this::toSearchHit)
                        .toList()
        ));
    }

    @GetMapping("/compare")
    public ApiResponse<RagCompareResponse> compare(
            @RequestParam("query") String query,
            @RequestParam(defaultValue = "5") int topK,
            @RequestParam(required = false) String sourceType
    ) {
        BaseContext.requireCurrentUserId();
        java.util.Map<String, Object> filters = new java.util.LinkedHashMap<>();
        if (sourceType != null && !sourceType.isBlank()) {
            filters.put("source_type", sourceType.trim());
        }

        KnowledgeSearchService.KnowledgeSearchDebugResult result = knowledgeSearchService.debugSearch(query, topK, filters);
        return ApiResponse.success(new RagCompareResponse(
                query,
                result.topK(),
                result.candidateTopK(),
                result.rerankEnabled(),
                result.rawHits().stream().map(this::toCompareHit).toList(),
                result.rerankedHits().stream().map(this::toCompareHit).toList()
        ));
    }

    private RagCompareResponse.RagCompareHitResponse toCompareHit(KnowledgeHit item) {
        return new RagCompareResponse.RagCompareHitResponse(
                item.chunkId(),
                item.sourceId(),
                item.sourceType(),
                item.title(),
                item.content(),
                item.score(),
                item.metadata()
        );
    }

    private RagSearchResponse.RagSearchHitResponse toSearchHit(Document item) {
        java.util.Map<String, Object> metadata = item.getMetadata() == null
                ? new java.util.LinkedHashMap<>()
                : new java.util.LinkedHashMap<>(item.getMetadata());
        return new RagSearchResponse.RagSearchHitResponse(
                readString(metadata, "chunk_id", item.getId()),
                readString(metadata, "source_id", ""),
                readString(metadata, "source_type", ""),
                readString(metadata, "title", ""),
                item.getText() == null ? "" : item.getText(),
                readDouble(item.getScore(), metadata.get("score")),
                metadata
        );
    }

    private String readString(java.util.Map<String, Object> metadata, String key, String fallback) {
        Object value = metadata.get(key);
        if (value == null) {
            return fallback;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() ? fallback : normalized;
    }

    private double readDouble(Double score, Object fallbackValue) {
        if (score != null) {
            return score;
        }
        if (fallbackValue instanceof Number number) {
            return number.doubleValue();
        }
        if (fallbackValue instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return 0.0d;
            }
        }
        return 0.0d;
    }

    private String extractErrorMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getMessage();
        }
        return message == null || message.isBlank() ? "rag_ingest_failed" : message;
    }
}
