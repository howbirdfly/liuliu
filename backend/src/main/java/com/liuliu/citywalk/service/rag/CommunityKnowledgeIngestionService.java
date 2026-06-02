package com.liuliu.citywalk.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper;

    public CommunityKnowledgeIngestionService(
            CommunityMapper communityMapper,
            EmbeddingService embeddingService,
            KnowledgeIngestionService knowledgeIngestionService,
            ObjectMapper objectMapper
    ) {
        this.communityMapper = communityMapper;
        this.embeddingService = embeddingService;
        this.knowledgeIngestionService = knowledgeIngestionService;
        this.objectMapper = objectMapper;
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
            String knowledgeText = buildWalkKnowledgeText(walk);
            if (knowledgeText.isBlank()) {
                continue;
            }
            walkIds.add(walk.getId());
            List<String> chunks = splitIntoChunks(knowledgeText, DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
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
