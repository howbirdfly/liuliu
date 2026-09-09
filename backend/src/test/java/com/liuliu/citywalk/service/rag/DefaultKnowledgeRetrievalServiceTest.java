package com.liuliu.citywalk.service.rag;

import com.liuliu.citywalk.config.RagProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultKnowledgeRetrievalServiceTest {

    @Test
    void fusesDenseAndKeywordRanksBySourceUsingNormalizedRrf() {
        VectorStore vectorStore = mock(VectorStore.class);
        CommunityWalkKeywordRecallService keywordRecallService = mock(CommunityWalkKeywordRecallService.class);
        RagProperties properties = new RagProperties();
        properties.setHybridRrfK(60);
        DefaultKnowledgeRetrievalService service = new DefaultKnowledgeRetrievalService(
                vectorStore,
                keywordRecallService,
                properties
        );
        VectorSearchQuery query = new VectorSearchQuery("上海 安静 拍照", List.of(0.1F), 5, Map.of());
        KnowledgeHit vectorA = hit("a:0", "a", 0.95D, "vector-a");
        KnowledgeHit vectorASecondChunk = hit("a:1", "a", 0.90D, "vector-a-second");
        KnowledgeHit vectorC = hit("c:0", "c", 0.72D, "vector-c");
        KnowledgeHit keywordC = hit("c:keyword", "c", 0.89D, "keyword-c");
        KnowledgeHit keywordD = hit("d:keyword", "d", 0.84D, "keyword-d");
        when(vectorStore.search(query)).thenReturn(List.of(vectorA, vectorASecondChunk, vectorC));
        when(keywordRecallService.recall(query.queryText(), query.topK(), query.filters()))
                .thenReturn(List.of(keywordC, keywordD));

        List<KnowledgeHit> result = service.retrieve(query);

        assertEquals(List.of("c", "a", "d"), result.stream().map(KnowledgeHit::sourceId).toList());
        assertEquals(3, result.size());
        KnowledgeHit fusedC = result.getFirst();
        assertEquals("c:0", fusedC.chunkId());
        assertEquals("vector-c", fusedC.content());
        assertEquals("rrf", fusedC.metadata().get("fusion_method"));
        assertEquals(2, ((Number) fusedC.metadata().get("vector_rank")).intValue());
        assertEquals(1, ((Number) fusedC.metadata().get("keyword_rank")).intValue());
        assertEquals(0.5D, result.get(1).score(), 0.000001D);
        assertTrue(fusedC.score() > result.get(1).score());
        verify(keywordRecallService).recall(query.queryText(), query.topK(), query.filters());
    }

    @Test
    void keepsDenseResultsUntouchedWhenKeywordRecallIsEmpty() {
        VectorStore vectorStore = mock(VectorStore.class);
        CommunityWalkKeywordRecallService keywordRecallService = mock(CommunityWalkKeywordRecallService.class);
        RagProperties properties = new RagProperties();
        DefaultKnowledgeRetrievalService service = new DefaultKnowledgeRetrievalService(
                vectorStore,
                keywordRecallService,
                properties
        );
        VectorSearchQuery query = new VectorSearchQuery("海边 日落", List.of(0.2F), 5, Map.of());
        List<KnowledgeHit> vectorHits = List.of(hit("a:0", "a", 0.91D, "vector-a"));
        when(vectorStore.search(query)).thenReturn(vectorHits);
        when(keywordRecallService.recall(query.queryText(), query.topK(), query.filters())).thenReturn(List.of());

        List<KnowledgeHit> result = service.retrieve(query);

        assertSame(vectorHits, result);
    }

    @Test
    void skipsKeywordRecallWhenHybridRecallIsDisabled() {
        VectorStore vectorStore = mock(VectorStore.class);
        CommunityWalkKeywordRecallService keywordRecallService = mock(CommunityWalkKeywordRecallService.class);
        RagProperties properties = new RagProperties();
        properties.setHybridKeywordRecallEnabled(false);
        DefaultKnowledgeRetrievalService service = new DefaultKnowledgeRetrievalService(
                vectorStore,
                keywordRecallService,
                properties
        );
        VectorSearchQuery query = new VectorSearchQuery("校园", List.of(0.3F), 5, Map.of());
        List<KnowledgeHit> vectorHits = List.of(hit("a:0", "a", 0.88D, "vector-a"));
        when(vectorStore.search(query)).thenReturn(vectorHits);

        List<KnowledgeHit> result = service.retrieve(query);

        assertSame(vectorHits, result);
        verifyNoInteractions(keywordRecallService);
    }

    private KnowledgeHit hit(String chunkId, String sourceId, double score, String content) {
        return new KnowledgeHit(
                chunkId,
                sourceId,
                "community_walk",
                "title-" + sourceId,
                content,
                score,
                Map.of("original", sourceId)
        );
    }
}
