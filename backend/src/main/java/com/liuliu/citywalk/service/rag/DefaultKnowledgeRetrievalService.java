package com.liuliu.citywalk.service.rag;

import com.liuliu.citywalk.config.RagProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        if (query == null) {
            return List.of();
        }
        List<KnowledgeHit> vectorHits = vectorStore.search(query);
        if (!ragProperties.isHybridKeywordRecallEnabled()) {
            return vectorHits;
        }

        List<KnowledgeHit> keywordHits = communityWalkKeywordRecallService.recall(
                query.queryText(),
                query.topK(),
                query.filters()
        );
        List<KnowledgeHit> normalizedVectorHits = vectorHits == null ? List.of() : vectorHits;
        List<KnowledgeHit> normalizedKeywordHits = keywordHits == null ? List.of() : keywordHits;
        if (normalizedKeywordHits.isEmpty()) {
            return normalizedVectorHits;
        }
        if (normalizedVectorHits.isEmpty()) {
            return normalizedKeywordHits.stream().limit(Math.max(1, query.topK())).toList();
        }
        return fuseWithReciprocalRankFusion(normalizedVectorHits, normalizedKeywordHits, query.topK());
    }

    private List<KnowledgeHit> fuseWithReciprocalRankFusion(
            List<KnowledgeHit> vectorHits,
            List<KnowledgeHit> keywordHits,
            int topK
    ) {
        Map<String, RankedHit> vectorBySource = rankDistinctSources(vectorHits);
        Map<String, RankedHit> keywordBySource = rankDistinctSources(keywordHits);
        Map<String, Boolean> sourceKeys = new LinkedHashMap<>();
        vectorBySource.keySet().forEach(key -> sourceKeys.put(key, true));
        keywordBySource.keySet().forEach(key -> sourceKeys.put(key, true));

        int rrfK = Math.max(1, ragProperties.getHybridRrfK());
        double maximumTwoChannelScore = 2D / (rrfK + 1D);
        List<KnowledgeHit> fused = new ArrayList<>(sourceKeys.size());
        for (String sourceKey : sourceKeys.keySet()) {
            RankedHit vector = vectorBySource.get(sourceKey);
            RankedHit keyword = keywordBySource.get(sourceKey);
            double rawRrfScore = reciprocalRank(vector, rrfK) + reciprocalRank(keyword, rrfK);
            double normalizedRrfScore = Math.min(1D, rawRrfScore / maximumTwoChannelScore);
            fused.add(buildFusedHit(vector, keyword, rawRrfScore, normalizedRrfScore, rrfK));
        }

        return fused.stream()
                .sorted(Comparator.comparingDouble(KnowledgeHit::score).reversed())
                .limit(Math.max(1, topK))
                .toList();
    }

    private Map<String, RankedHit> rankDistinctSources(List<KnowledgeHit> hits) {
        Map<String, RankedHit> rankedBySource = new LinkedHashMap<>();
        if (hits == null || hits.isEmpty()) {
            return rankedBySource;
        }
        for (KnowledgeHit hit : hits) {
            if (hit == null) {
                continue;
            }
            String sourceKey = buildSourceKey(hit);
            if (!rankedBySource.containsKey(sourceKey)) {
                rankedBySource.put(sourceKey, new RankedHit(hit, rankedBySource.size() + 1));
            }
        }
        return rankedBySource;
    }

    private double reciprocalRank(RankedHit rankedHit, int rrfK) {
        return rankedHit == null ? 0D : 1D / (rrfK + rankedHit.rank());
    }

    private KnowledgeHit buildFusedHit(
            RankedHit vector,
            RankedHit keyword,
            double rawRrfScore,
            double normalizedRrfScore,
            int rrfK
    ) {
        KnowledgeHit vectorHit = vector == null ? null : vector.hit();
        KnowledgeHit keywordHit = keyword == null ? null : keyword.hit();
        KnowledgeHit primaryHit = vectorHit == null ? keywordHit : vectorHit;
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (keywordHit != null && keywordHit.metadata() != null && !keywordHit.metadata().isEmpty()) {
            metadata.putAll(keywordHit.metadata());
        }
        if (vectorHit != null && vectorHit.metadata() != null && !vectorHit.metadata().isEmpty()) {
            metadata.putAll(vectorHit.metadata());
        }
        metadata.put("fusion_method", "rrf");
        metadata.put("rrf_k", rrfK);
        metadata.put("rrf_raw_score", rawRrfScore);
        metadata.put("rrf_normalized_score", normalizedRrfScore);
        metadata.put("vector_rank", vector == null ? null : vector.rank());
        metadata.put("vector_score", vectorHit == null ? null : vectorHit.score());
        metadata.put("keyword_rank", keyword == null ? null : keyword.rank());
        metadata.put("keyword_score", keywordHit == null ? null : keywordHit.score());

        return new KnowledgeHit(
                primaryHit.chunkId(),
                primaryHit.sourceId(),
                primaryHit.sourceType(),
                primaryHit.title(),
                primaryHit.content(),
                normalizedRrfScore,
                metadata
        );
    }

    private String buildSourceKey(KnowledgeHit hit) {
        String sourceType = Objects.toString(hit.sourceType(), "").trim();
        String sourceId = Objects.toString(hit.sourceId(), "").trim();
        if (!sourceId.isBlank()) {
            return sourceType + ":" + sourceId;
        }
        String chunkId = Objects.toString(hit.chunkId(), "").trim();
        if (!chunkId.isBlank()) {
            return "chunk:" + chunkId;
        }
        return "content:" + Objects.hash(hit.title(), hit.content());
    }

    private record RankedHit(KnowledgeHit hit, int rank) {
    }
}
