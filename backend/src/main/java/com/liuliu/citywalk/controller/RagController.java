package com.liuliu.citywalk.controller;

import com.liuliu.citywalk.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.liuliu.citywalk.context.BaseContext;
import com.liuliu.citywalk.model.dto.response.RagHealthResponse;
import com.liuliu.citywalk.model.dto.response.RagIngestionResponse;
import com.liuliu.citywalk.model.dto.response.RagSearchResponse;
import com.liuliu.citywalk.service.rag.CommunityKnowledgeIngestionResult;
import com.liuliu.citywalk.service.rag.CommunityKnowledgeIngestionService;
import com.liuliu.citywalk.service.rag.EmbeddingService;
import com.liuliu.citywalk.service.rag.KnowledgeHit;
import com.liuliu.citywalk.service.rag.KnowledgeSearchService;
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

    public RagController(
            VectorStore vectorStore,
            EmbeddingService embeddingService,
            CommunityKnowledgeIngestionService communityKnowledgeIngestionService,
            KnowledgeSearchService knowledgeSearchService
    ) {
        this.vectorStore = vectorStore;
        this.embeddingService = embeddingService;
        this.communityKnowledgeIngestionService = communityKnowledgeIngestionService;
        this.knowledgeSearchService = knowledgeSearchService;
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
        java.util.List<KnowledgeHit> hits = knowledgeSearchService.search(query, topK, filters);
        return ApiResponse.success(new RagSearchResponse(
                query,
                Math.max(1, topK),
                hits.stream()
                        .map(item -> new RagSearchResponse.RagSearchHitResponse(
                                item.chunkId(),
                                item.sourceId(),
                                item.sourceType(),
                                item.title(),
                                item.content(),
                                item.score(),
                                item.metadata()
                        ))
                        .toList()
        ));
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
