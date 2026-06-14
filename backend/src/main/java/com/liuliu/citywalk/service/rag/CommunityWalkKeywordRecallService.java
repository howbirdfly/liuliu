package com.liuliu.citywalk.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.config.RagProperties;
import com.liuliu.citywalk.mapper.CommunityMapper;
import com.liuliu.citywalk.mapper.entity.CommunityWalkQueryRow;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class CommunityWalkKeywordRecallService {

    private static final String COMMUNITY_WALK_SOURCE_TYPE = "community_walk";
    private static final Set<String> GENERIC_QUERY_TERMS = Set.of(
            "citywalk", "拍照", "出片", "摄影", "散步", "漫步", "晚霞", "日落", "夕阳",
            "夜景", "海边", "海滨", "海风", "打卡", "咖啡"
    );

    private static final Map<String, List<String>> QUERY_SYNONYMS = Map.ofEntries(
            Map.entry("中珠", List.of("中山大学珠海校区", "中大珠海")),
            Map.entry("中大珠海", List.of("中山大学珠海校区", "中珠")),
            Map.entry("晚霞", List.of("日落", "夕阳")),
            Map.entry("拍照", List.of("出片", "摄影")),
            Map.entry("散步", List.of("漫步", "citywalk")),
            Map.entry("海边", List.of("海滨", "海风", "沙滩"))
    );

    private final CommunityMapper communityMapper;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;

    public CommunityWalkKeywordRecallService(
            CommunityMapper communityMapper,
            RagProperties ragProperties,
            ObjectMapper objectMapper
    ) {
        this.communityMapper = communityMapper;
        this.ragProperties = ragProperties;
        this.objectMapper = objectMapper;
    }

    public List<KnowledgeHit> recall(String queryText, int topK, Map<String, Object> filters) {
        if (!ragProperties.isHybridKeywordRecallEnabled()) {
            return List.of();
        }
        if (!supportsFilters(filters)) {
            return List.of();
        }

        List<String> variants = buildVariants(queryText, ragProperties.getHybridKeywordMaxVariants());
        List<String> anchorVariants = buildAnchorVariants(queryText, ragProperties.getHybridKeywordMaxVariants());
        if (variants.isEmpty()) {
            return List.of();
        }

        int perVariantLimit = Math.max(1, ragProperties.getHybridKeywordPerVariantLimit());
        Map<Long, KnowledgeHit> hitsByWalkId = new LinkedHashMap<>();
        for (String variant : variants) {
            List<CommunityWalkQueryRow> rows = communityMapper.searchPublicWalks(variant, null, 1, perVariantLimit);
            if (rows == null || rows.isEmpty()) {
                continue;
            }
            for (CommunityWalkQueryRow row : rows) {
                if (row == null || row.getId() == null) {
                    continue;
                }
                KnowledgeHit candidate = toKnowledgeHit(row, variant, variants, anchorVariants);
                if (candidate == null) {
                    continue;
                }
                KnowledgeHit existing = hitsByWalkId.get(row.getId());
                if (existing == null || candidate.score() > existing.score()) {
                    hitsByWalkId.put(row.getId(), candidate);
                }
            }
        }

        return hitsByWalkId.values().stream()
                .sorted(Comparator.comparingDouble(KnowledgeHit::score).reversed())
                .limit(Math.max(1, topK))
                .toList();
    }

    private boolean supportsFilters(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        Object sourceType = filters.get("source_type");
        if (sourceType != null) {
            String normalized = sourceType.toString().trim();
            if (!normalized.isBlank() && !COMMUNITY_WALK_SOURCE_TYPE.equals(normalized)) {
                return false;
            }
        }
        Object sourceId = filters.get("source_id");
        return sourceId == null || !sourceId.toString().trim().isBlank();
    }

    private KnowledgeHit toKnowledgeHit(
            CommunityWalkQueryRow row,
            String matchedVariant,
            List<String> variants,
            List<String> anchorVariants
    ) {
        if (!matchesAnyAnchor(row, anchorVariants)) {
            return null;
        }
        Map<String, Object> metadata = buildMetadata(row, matchedVariant);
        String title = defaultText(row.getThemeTitle(), "City Walk");
        String content = buildSearchContent(row);
        double score = computeKeywordScore(row, variants, anchorVariants);
        return new KnowledgeHit(
                "community:" + row.getId() + ":0",
                String.valueOf(row.getId()),
                COMMUNITY_WALK_SOURCE_TYPE,
                title,
                content,
                score,
                metadata
        );
    }

    private Map<String, Object> buildMetadata(CommunityWalkQueryRow row, String matchedVariant) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("location_name", row.getLocationName());
        metadata.put("author_nickname", row.getAuthorNickname());
        metadata.put("tags", row.getTags());
        metadata.put("created_at", row.getCreatedAt() == null ? null : row.getCreatedAt().toInstant().toString());
        metadata.put("keyword_match", matchedVariant);
        metadata.put("recall_source", "mysql_keyword");
        return metadata;
    }

    private double computeKeywordScore(CommunityWalkQueryRow row, List<String> variants, List<String> anchorVariants) {
        String title = normalizeText(row.getThemeTitle());
        String location = normalizeText(row.getLocationName());
        String tags = normalizeText(row.getTags());
        String note = normalizeText(row.getNoteText());
        String themeDescription = normalizeText(extractThemeField(row.getThemeSnapshot(), "description"));

        double score = 0.34D;
        double bestVariantCoverage = 0D;
        for (String variant : variants) {
            String normalizedVariant = normalizeText(variant);
            if (normalizedVariant.isBlank()) {
                continue;
            }
            bestVariantCoverage = Math.max(bestVariantCoverage, fieldCoverage(normalizedVariant, title, location, tags, note, themeDescription));
            if (title.contains(normalizedVariant)) {
                score += 0.15D;
            }
            if (location.contains(normalizedVariant)) {
                score += 0.18D;
            }
            if (tags.contains(normalizedVariant)) {
                score += 0.20D;
            }
            if (note.contains(normalizedVariant) || themeDescription.contains(normalizedVariant)) {
                score += 0.08D;
            }
        }

        score += anchorBoost(anchorVariants, title, location, tags, note, themeDescription);
        score += Math.min(0.12D, bestVariantCoverage * 0.12D);
        score += recencyBoost(row.getCreatedAt());
        return Math.min(0.92D, score);
    }

    private double anchorBoost(
            List<String> anchorVariants,
            String title,
            String location,
            String tags,
            String note,
            String themeDescription
    ) {
        if (anchorVariants == null || anchorVariants.isEmpty()) {
            return 0D;
        }
        for (String anchor : anchorVariants) {
            String normalizedAnchor = normalizeText(anchor);
            if (normalizedAnchor.isBlank()) {
                continue;
            }
            if (location.contains(normalizedAnchor)) {
                return 0.18D;
            }
            if (title.contains(normalizedAnchor)) {
                return 0.14D;
            }
            if (tags.contains(normalizedAnchor) || note.contains(normalizedAnchor) || themeDescription.contains(normalizedAnchor)) {
                return 0.10D;
            }
        }
        return 0D;
    }

    private double fieldCoverage(
            String normalizedVariant,
            String title,
            String location,
            String tags,
            String note,
            String themeDescription
    ) {
        int matchedFields = 0;
        if (title.contains(normalizedVariant)) {
            matchedFields++;
        }
        if (location.contains(normalizedVariant)) {
            matchedFields++;
        }
        if (tags.contains(normalizedVariant)) {
            matchedFields++;
        }
        if (note.contains(normalizedVariant) || themeDescription.contains(normalizedVariant)) {
            matchedFields++;
        }
        return matchedFields / 4.0D;
    }

    private double recencyBoost(Timestamp createdAt) {
        if (createdAt == null) {
            return 0D;
        }
        try {
            Instant instant = createdAt.toInstant();
            long days = Math.max(0L, Duration.between(instant, Instant.now()).toDays());
            if (days <= 30L) {
                return 0.04D;
            }
            if (days <= 90L) {
                return 0.02D;
            }
            return 0D;
        } catch (Exception error) {
            return 0D;
        }
    }

    private List<String> buildVariants(String queryText, int maxVariants) {
        String normalizedQuery = defaultText(queryText, "").trim();
        if (normalizedQuery.isBlank()) {
            return List.of();
        }

        Set<String> variants = new LinkedHashSet<>();
        for (String segment : normalizedQuery.split("\\s+")) {
            addVariant(variants, segment);
            for (String synonym : QUERY_SYNONYMS.getOrDefault(segment.trim().toLowerCase(Locale.ROOT), List.of())) {
                addVariant(variants, synonym);
            }
        }

        String compact = normalizedQuery.replaceAll("\\s+", "");
        if (!compact.equals(normalizedQuery)) {
            addVariant(variants, compact);
        }

        for (Map.Entry<String, List<String>> entry : QUERY_SYNONYMS.entrySet()) {
            if (normalizedQuery.contains(entry.getKey())) {
                addVariant(variants, entry.getKey());
                for (String synonym : entry.getValue()) {
                    addVariant(variants, synonym);
                }
            }
        }

        return variants.stream()
                .limit(Math.max(1, maxVariants))
                .toList();
    }

    private List<String> buildAnchorVariants(String queryText, int maxVariants) {
        String normalizedQuery = defaultText(queryText, "").trim();
        if (normalizedQuery.isBlank()) {
            return List.of();
        }
        Set<String> anchors = new LinkedHashSet<>();
        for (String segment : normalizedQuery.split("\\s+")) {
            String normalizedSegment = segment.trim().toLowerCase(Locale.ROOT);
            if (normalizedSegment.length() < 2 || GENERIC_QUERY_TERMS.contains(normalizedSegment)) {
                continue;
            }
            addVariant(anchors, segment);
            for (String synonym : QUERY_SYNONYMS.getOrDefault(normalizedSegment, List.of())) {
                addVariant(anchors, synonym);
            }
        }
        return anchors.stream()
                .limit(Math.max(1, maxVariants))
                .toList();
    }

    private boolean matchesAnyAnchor(CommunityWalkQueryRow row, List<String> anchorVariants) {
        if (anchorVariants == null || anchorVariants.isEmpty()) {
            return true;
        }
        String title = normalizeText(row.getThemeTitle());
        String location = normalizeText(row.getLocationName());
        String tags = normalizeText(row.getTags());
        String note = normalizeText(row.getNoteText());
        String themeDescription = normalizeText(extractThemeField(row.getThemeSnapshot(), "description"));
        for (String anchor : anchorVariants) {
            String normalizedAnchor = normalizeText(anchor);
            if (normalizedAnchor.isBlank()) {
                continue;
            }
            if (title.contains(normalizedAnchor)
                    || location.contains(normalizedAnchor)
                    || tags.contains(normalizedAnchor)
                    || note.contains(normalizedAnchor)
                    || themeDescription.contains(normalizedAnchor)) {
                return true;
            }
        }
        return false;
    }

    private void addVariant(Set<String> variants, String candidate) {
        String normalized = defaultText(candidate, "").trim();
        if (normalized.length() < 2) {
            return;
        }
        variants.add(normalized);
    }

    private String buildSearchContent(CommunityWalkQueryRow row) {
        List<String> segments = new ArrayList<>();
        appendSegment(segments, "title", row.getThemeTitle());
        appendSegment(segments, "location", row.getLocationName());
        appendSegment(segments, "tags", normalizeTags(row.getTags()));
        appendSegment(segments, "category", extractThemeField(row.getThemeSnapshot(), "category"));
        appendSegment(segments, "description", extractThemeField(row.getThemeSnapshot(), "description"));
        appendSegment(segments, "missions", normalizeMissionList(row.getMissionsCompleted()));
        appendSegment(segments, "note", cleanSentence(row.getNoteText()));
        return String.join("\n", segments);
    }

    private void appendSegment(List<String> segments, String label, String value) {
        String normalized = defaultText(value, "");
        if (normalized.isBlank()) {
            return;
        }
        segments.add(label + ": " + normalized);
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
            return String.join("; ", missions);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String normalizeTags(String tags) {
        return cleanSentence(defaultText(tags, "")
                .replace("||", ", ")
                .replace(",", ", "));
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

    private String normalizeText(String text) {
        return defaultText(text, "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "")
                .trim();
    }

    private String defaultText(String text, String fallback) {
        if (text == null) {
            return fallback;
        }
        String normalized = text.trim();
        return normalized.isEmpty() ? fallback : normalized;
    }
}
