package com.liuliu.citywalk.controller;

import com.liuliu.citywalk.common.ApiResponse;
import com.liuliu.citywalk.context.BaseContext;
import com.liuliu.citywalk.model.dto.response.RagHealthResponse;
import com.liuliu.citywalk.model.dto.response.RagIngestionResponse;
import com.liuliu.citywalk.service.rag.CommunityKnowledgeIngestionResult;
import com.liuliu.citywalk.service.rag.CommunityKnowledgeIngestionService;
import com.liuliu.citywalk.service.rag.EmbeddingService;
import com.liuliu.citywalk.service.rag.VectorStore;
import com.liuliu.citywalk.service.rag.VectorStoreHealth;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rag")
public class RagController {

    private final VectorStore vectorStore;
    private final EmbeddingService embeddingService;
    private final CommunityKnowledgeIngestionService communityKnowledgeIngestionService;

    public RagController(
            VectorStore vectorStore,
            EmbeddingService embeddingService,
            CommunityKnowledgeIngestionService communityKnowledgeIngestionService
    ) {
        this.vectorStore = vectorStore;
        this.embeddingService = embeddingService;
        this.communityKnowledgeIngestionService = communityKnowledgeIngestionService;
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
        BaseContext.requireCurrentUserId();
        CommunityKnowledgeIngestionResult result = communityKnowledgeIngestionService.ingestLatestPublicWalks(limit, offset);
        return ApiResponse.success(new RagIngestionResponse(
                result.walkCount(),
                result.chunkCount(),
                result.walkIds()
        ));
    }
}
