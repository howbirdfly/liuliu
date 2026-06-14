package com.liuliu.citywalk.service.rag;

import com.liuliu.citywalk.config.RagProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DefaultKnowledgeRetrievalService implements KnowledgeRetrievalService {

    private final VectorStore vectorStore;
    private final CommunityWalkKeywordRecallService communityWalkKeywordRecallService;
    private final RagProperties ragProperties;

    public DefaultKnowledgeRetrievalService(
            VectorStore vectorStore,
            CommunityWalkKeywordRecallService communityWalkKeywordRecallService,
            RagProperties ragProperties
    ) {
        this.vectorStore = vectorStore;
        this.communityWalkKeywordRecallService = communityWalkKeywordRecallService;
        this.ragProperties = ragProperties;
    }

    @Override
    public List<KnowledgeHit> retrieve(VectorSearchQuery query) {
        List<KnowledgeHit> vectorHits = vectorStore.search(query);
        if (query == null || !ragProperties.isHybridKeywordRecallEnabled()) {
            return vectorHits;
        }

        List<KnowledgeHit> keywordHits = communityWalkKeywordRecallService.recall(
                query.queryText(),
                query.topK(),
                query.filters()
        );
        if (keywordHits.isEmpty()) {
            return vectorHits;
        }
        return mergeHits(vectorHits, keywordHits, query.topK());
    }

    private List<KnowledgeHit> mergeHits(List<KnowledgeHit> vectorHits, List<KnowledgeHit> keywordHits, int topK) {
        Map<String, KnowledgeHit> keywordBySource = new LinkedHashMap<>();
        for (KnowledgeHit hit : keywordHits) {
            keywordBySource.put(buildSourceKey(hit), hit);
        }

        List<KnowledgeHit> merged = new ArrayList<>();
        Map<String, KnowledgeHit> mergedByChunk = new LinkedHashMap<>();
        for (KnowledgeHit vectorHit : vectorHits) {
            KnowledgeHit keywordHit = keywordBySource.get(buildSourceKey(vectorHit));
            KnowledgeHit mergedHit = keywordHit == null ? vectorHit : mergeVectorAndKeywordHit(vectorHit, keywordHit);
            merged.add(mergedHit);
            mergedByChunk.put(buildChunkKey(mergedHit), mergedHit);
        }

        for (KnowledgeHit keywordHit : keywordHits) {
            String chunkKey = buildChunkKey(keywordHit);
            if (!mergedByChunk.containsKey(chunkKey)) {
                merged.add(keywordHit);
                mergedByChunk.put(chunkKey, keywordHit);
            }
        }

        return merged.stream()
                .sorted(Comparator.comparingDouble(KnowledgeHit::score).reversed())
                .limit(Math.max(1, topK))
                .toList();
    }

    private KnowledgeHit mergeVectorAndKeywordHit(KnowledgeHit vectorHit, KnowledgeHit keywordHit) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (keywordHit.metadata() != null && !keywordHit.metadata().isEmpty()) {
            metadata.putAll(keywordHit.metadata());
        }
        if (vectorHit.metadata() != null && !vectorHit.metadata().isEmpty()) {
            metadata.putAll(vectorHit.metadata());
        }
        return new KnowledgeHit(
                vectorHit.chunkId(),
                vectorHit.sourceId(),
                vectorHit.sourceType(),
                vectorHit.title(),
                vectorHit.content(),
                Math.max(vectorHit.score(), keywordHit.score()),
                metadata
        );
    }

    private String buildSourceKey(KnowledgeHit hit) {
        return hit.sourceType() + ":" + hit.sourceId();
    }

    private String buildChunkKey(KnowledgeHit hit) {
        return hit.sourceType() + ":" + hit.sourceId() + ":" + hit.chunkId();
    }
}
