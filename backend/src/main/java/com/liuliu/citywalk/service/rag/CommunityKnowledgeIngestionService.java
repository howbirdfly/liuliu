package com.liuliu.citywalk.service.rag;

import com.liuliu.citywalk.mapper.CommunityMapper;
import com.liuliu.citywalk.mapper.entity.CommunityWalkQueryRow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CommunityKnowledgeIngestionService {

    private static final int DEFAULT_CHUNK_SIZE = 520;
    private static final int DEFAULT_CHUNK_OVERLAP = 80;

    private final CommunityMapper communityMapper;
    private final EmbeddingService embeddingService;
    private final KnowledgeIngestionService knowledgeIngestionService;

    public CommunityKnowledgeIngestionService(
            CommunityMapper communityMapper,
            EmbeddingService embeddingService,
            KnowledgeIngestionService knowledgeIngestionService
    ) {
        this.communityMapper = communityMapper;
        this.embeddingService = embeddingService;
        this.knowledgeIngestionService = knowledgeIngestionService;
    }

    public CommunityKnowledgeIngestionResult ingestLatestPublicWalks(int limit, int offset) {
        int normalizedLimit = Math.max(1, Math.min(limit, 200));
        int normalizedOffset = Math.max(0, offset);
        List<CommunityWalkQueryRow> walks = communityMapper.listLatestPublicWalks(null, normalizedLimit, normalizedOffset);
        if (walks == null || walks.isEmpty()) {
            return new CommunityKnowledgeIngestionResult(0, 0, List.of());
        }

        List<ChunkDraft> drafts = new ArrayList<>();
        List<Long> walkIds = new ArrayList<>();
        for (CommunityWalkQueryRow walk : walks) {
            if (walk == null || walk.getId() == null) {
                continue;
            }
            walkIds.add(walk.getId());
            List<String> chunks = splitIntoChunks(buildWalkKnowledgeText(walk), DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
            for (int index = 0; index < chunks.size(); index++) {
                String chunk = chunks.get(index);
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("chunk_index", index);
                metadata.put("location_name", walk.getLocationName());
                metadata.put("author_nickname", walk.getAuthorNickname());
                metadata.put("tags", walk.getTags());
                metadata.put("created_at", walk.getCreatedAt() == null ? null : walk.getCreatedAt().toInstant().toString());
                drafts.add(new ChunkDraft(
                        "community:" + walk.getId() + ":" + index,
                        String.valueOf(walk.getId()),
                        "community_walk",
                        defaultText(walk.getThemeTitle(), "City Walk 公开路线"),
                        chunk,
                        metadata
                ));
            }
        }

        if (drafts.isEmpty()) {
            return new CommunityKnowledgeIngestionResult(walkIds.size(), 0, walkIds);
        }

        List<List<Float>> embeddings = embeddingService.embedAll(drafts.stream().map(ChunkDraft::content).toList());
        if (embeddings.size() != drafts.size()) {
            throw new IllegalStateException("embedding_count_mismatch");
        }

        List<KnowledgeDocument> documents = new ArrayList<>(drafts.size());
        for (int index = 0; index < drafts.size(); index++) {
            ChunkDraft draft = drafts.get(index);
            documents.add(new KnowledgeDocument(
                    draft.chunkId(),
                    draft.sourceId(),
                    draft.sourceType(),
                    draft.title(),
                    draft.content(),
                    embeddings.get(index),
                    draft.metadata()
            ));
        }
        knowledgeIngestionService.upsert(documents);
        return new CommunityKnowledgeIngestionResult(walkIds.size(), documents.size(), walkIds);
    }

    private String buildWalkKnowledgeText(CommunityWalkQueryRow walk) {
        StringBuilder builder = new StringBuilder();
        appendSection(builder, "主题标题", walk.getThemeTitle());
        appendSection(builder, "地点名称", walk.getLocationName());
        appendSection(builder, "作者昵称", walk.getAuthorNickname());
        appendSection(builder, "标签", walk.getTags() == null ? "" : walk.getTags().replace("||", "、"));
        appendSection(builder, "主题快照", walk.getThemeSnapshot());
        appendSection(builder, "路线点位", walk.getRoutePoints());
        appendSection(builder, "已完成任务", walk.getMissionsCompleted());
        appendSection(builder, "漫步备注", walk.getNoteText());
        return builder.toString().trim();
    }

    private void appendSection(StringBuilder builder, String label, String content) {
        String normalizedContent = defaultText(content, "");
        if (normalizedContent.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("\n\n");
        }
        builder.append(label).append("：").append(normalizedContent);
    }

    private List<String> splitIntoChunks(String text, int chunkSize, int overlap) {
        String normalizedText = defaultText(text, "").trim();
        if (normalizedText.isBlank()) {
            return List.of();
        }
        if (normalizedText.length() <= chunkSize) {
            return List.of(normalizedText);
        }

        List<String> chunks = new ArrayList<>();
        int safeOverlap = Math.max(0, Math.min(overlap, chunkSize / 2));
        int start = 0;
        while (start < normalizedText.length()) {
            int end = Math.min(normalizedText.length(), start + chunkSize);
            chunks.add(normalizedText.substring(start, end).trim());
            if (end >= normalizedText.length()) {
                break;
            }
            start = Math.max(end - safeOverlap, start + 1);
        }
        return chunks;
    }

    private String defaultText(String text, String fallback) {
        if (text == null) {
            return fallback;
        }
        String normalized = text.trim();
        return normalized.isEmpty() ? fallback : normalized;
    }

    private record ChunkDraft(
            String chunkId,
            String sourceId,
            String sourceType,
            String title,
            String content,
            Map<String, Object> metadata
    ) {
    }
}
