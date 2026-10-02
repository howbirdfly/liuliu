package com.liuliu.citywalk.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.mapper.CommunityMapper;
import com.liuliu.citywalk.mapper.entity.CommunityWalkQueryRow;
import org.springframework.ai.document.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CommunityKnowledgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(CommunityKnowledgeIngestionService.class);

    private final CommunityMapper communityMapper;
    private final SpringAiKnowledgeDocumentService springAiKnowledgeDocumentService;
    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;
    private final KnowledgeTextChunker knowledgeTextChunker;

    public CommunityKnowledgeIngestionService(
            CommunityMapper communityMapper,
            SpringAiKnowledgeDocumentService springAiKnowledgeDocumentService,
            VectorStore vectorStore,
            ObjectMapper objectMapper,
            KnowledgeTextChunker knowledgeTextChunker
    ) {
        this.communityMapper = communityMapper;
        this.springAiKnowledgeDocumentService = springAiKnowledgeDocumentService;
        this.vectorStore = vectorStore;
        this.objectMapper = objectMapper;
        this.knowledgeTextChunker = knowledgeTextChunker;
    }

    public CommunityKnowledgeIngestionResult ingestLatestPublicWalks(int limit, int offset) {
        if (!isReady()) {
            return new CommunityKnowledgeIngestionResult(0, 0, List.of());
        }
        int normalizedLimit = Math.max(1, Math.min(limit, 200));
        int normalizedOffset = Math.max(0, offset);
        List<CommunityWalkQueryRow> walks = communityMapper.listLatestPublicWalks(null, normalizedLimit, normalizedOffset);
        if (walks == null || walks.isEmpty()) {
            return new CommunityKnowledgeIngestionResult(0, 0, List.of());
        }

        Map<Long, List<ChunkDraft>> draftsByWalkId = new LinkedHashMap<>();
        List<Long> walkIds = new ArrayList<>();
        for (CommunityWalkQueryRow walk : walks) {
            if (walk == null || walk.getId() == null) {
                continue;
            }
            String knowledgeText = buildWalkKnowledgeText(walk);
            if (knowledgeText.isBlank()) {
                continue;
            }
            walkIds.add(walk.getId());
            draftsByWalkId.put(walk.getId(), toChunkDrafts(walk, knowledgeTextChunker.split(knowledgeText)));
        }

        if (draftsByWalkId.isEmpty()) {
            return new CommunityKnowledgeIngestionResult(walkIds.size(), 0, walkIds);
        }

        int chunkCount = 0;
        for (Map.Entry<Long, List<ChunkDraft>> entry : draftsByWalkId.entrySet()) {
            List<Document> documents = entry.getValue().stream()
                    .map(this::toSpringAiDocument)
                    .toList();
            if (documents.isEmpty()) {
                continue;
            }
            // 分片规则变化后 chunk 数量会变，upsert 覆盖不到已经不存在的旧 chunkId，所以先按来源整体替换。
            springAiKnowledgeDocumentService.replaceBySource(
                    "community_walk",
                    String.valueOf(entry.getKey()),
                    documents
            );
            chunkCount += documents.size();
        }
        return new CommunityKnowledgeIngestionResult(walkIds.size(), chunkCount, walkIds);
    }

    public boolean syncPublicWalkById(Long walkId) {
        if (walkId == null || walkId <= 0L || !isReady()) {
            return false;
        }
        CommunityWalkQueryRow walk = communityMapper.findPublicWalkById(walkId, null);
        if (walk == null) {
            removeWalkById(walkId);
            return false;
        }

        List<ChunkDraft> drafts = buildChunkDrafts(walk);
        if (drafts.isEmpty()) {
            removeWalkById(walkId);
            return false;
        }

        List<Document> documents = drafts.stream()
                .map(this::toSpringAiDocument)
                .toList();
        // 先删同来源旧分片再写入：chunk 数量变少时，upsert 覆盖不到已经不存在的旧 index。
        springAiKnowledgeDocumentService.replaceBySource("community_walk", String.valueOf(walkId), documents);
        log.info("Synced public walk into Milvus, walkId={}, chunkCount={}", walkId, documents.size());
        return true;
    }

    public void removeWalkById(Long walkId) {
        if (walkId == null || walkId <= 0L || !vectorStore.isEnabled()) {
            return;
        }
        springAiKnowledgeDocumentService.removeBySource("community_walk", String.valueOf(walkId));
        log.info("Removed public walk knowledge from Milvus, walkId={}", walkId);
    }

    public boolean isReady() {
        return springAiKnowledgeDocumentService.isReady();
    }

    private List<ChunkDraft> buildChunkDrafts(CommunityWalkQueryRow walk) {
        if (walk == null || walk.getId() == null) {
            return List.of();
        }
        String knowledgeText = buildWalkKnowledgeText(walk);
        if (knowledgeText.isBlank()) {
            return List.of();
        }
        return toChunkDrafts(walk, knowledgeTextChunker.split(knowledgeText));
    }

    private List<ChunkDraft> toChunkDrafts(CommunityWalkQueryRow walk, List<String> chunks) {
        List<ChunkDraft> drafts = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            String chunk = chunks.get(index);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("chunk_index", index);
            metadata.put("chunk_count", chunks.size());
            // Spring AI 的 metadata 不允许 null 值,字段缺失时直接跳过。
            putIfNotNull(metadata, "location_name", walk.getLocationName());
            putIfNotNull(metadata, "author_nickname", walk.getAuthorNickname());
            putIfNotNull(metadata, "tags", walk.getTags());
            if (walk.getCreatedAt() != null) {
                metadata.put("created_at", walk.getCreatedAt().toInstant().toString());
            }
            drafts.add(new ChunkDraft(
                    "community:" + walk.getId() + ":" + index,
                    String.valueOf(walk.getId()),
                    "community_walk",
                    defaultText(walk.getThemeTitle(), "City Walk 公开路线"),
                    chunk,
                    metadata
            ));
        }
        return drafts;
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private String buildWalkKnowledgeText(CommunityWalkQueryRow walk) {
        StringBuilder builder = new StringBuilder();
        appendSection(builder, "主题标题", cleanSentence(walk.getThemeTitle()));
        appendSection(builder, "地点名称", cleanSentence(walk.getLocationName()));
        appendSection(builder, "标签", normalizeTags(walk.getTags()));
        appendSection(builder, "主题分类", extractThemeField(walk.getThemeSnapshot(), "category"));
        appendSection(builder, "主题描述", extractThemeField(walk.getThemeSnapshot(), "description"));
        appendSection(builder, "路线任务", normalizeMissionList(walk.getMissionsCompleted()));
        appendSection(builder, "漫步备注", cleanNoteText(walk.getNoteText()));
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

    private Document toSpringAiDocument(ChunkDraft draft) {
        Map<String, Object> metadata = new LinkedHashMap<>(draft.metadata());
        metadata.putIfAbsent("chunk_id", draft.chunkId());
        metadata.putIfAbsent("source_id", draft.sourceId());
        metadata.putIfAbsent("source_type", draft.sourceType());
        metadata.putIfAbsent("title", draft.title());
        return new Document(
                draft.chunkId(),
                draft.content(),
                metadata
        );
    }

    private String defaultText(String text, String fallback) {
        if (text == null) {
            return fallback;
        }
        String normalized = text.trim();
        return normalized.isEmpty() ? fallback : normalized;
    }

    private String extractThemeField(String themeSnapshot, String fieldName) {
        String normalizedSnapshot = defaultText(themeSnapshot, "");
        if (normalizedSnapshot.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(normalizedSnapshot);
            return cleanSentence(root.path(fieldName).asText(""));
        } catch (Exception ignored) {
            return "";
        }
    }

    private String normalizeTags(String tags) {
        String normalized = defaultText(tags, "")
                .replace("||", "、")
                .replace(",", "、")
                .replace("，", "、")
                .trim();
        return cleanSentence(normalized);
    }

    private String normalizeMissionList(String missionsCompleted) {
        String normalizedMissions = defaultText(missionsCompleted, "");
        if (normalizedMissions.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(normalizedMissions);
            if (!root.isArray()) {
                return "";
            }
            List<String> missions = new ArrayList<>();
            for (JsonNode item : root) {
                String mission = cleanSentence(item.asText(""));
                if (!mission.isBlank()) {
                    missions.add(mission);
                }
            }
            return String.join("；", missions);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String cleanNoteText(String noteText) {
        String normalized = cleanSentence(noteText);
        if (normalized.matches("[0-9\\p{Punct}\\s]+")) {
            return "";
        }
        return normalized;
    }

    private String cleanSentence(String text) {
        String normalized = defaultText(text, "")
                .replace("\\n", " ")
                .replace("\n", " ")
                .replace("\r", " ")
                .replace("\t", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.startsWith("{") || normalized.startsWith("[")) {
            return "";
        }
        return normalized;
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
