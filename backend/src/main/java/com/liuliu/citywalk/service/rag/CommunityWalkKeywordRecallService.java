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
    private static final int MAX_GRAMS_PER_LENGTH = 4;
    /** 疑问词、助词、口语连接词等,含有这些字符的 n-gram 直接丢弃,避免生成噪音词项。 */
    private static final Set<Character> QUERY_STOP_CHARS = Set.of(
            '的', '了', '在', '想', '去', '找', '有', '吗', '呢', '吧', '和', '与', '或',
            '是', '我', '你', '他', '她', '它', '这', '那', '么', '怎', '请', '帮', '推',
            '荐', '附', '近', '周', '末', '一', '下', '个', '条', '走', '逛', '适', '合',
            '可', '以', '要', '还', '都', '就', '能', '会', '给', '把', '被', '让', '从',
            '到', '对', '为', '于', '及', '并', '但', '而', '什', '哪', '里', '多', '少'
    );
    private static final Set<String> GENERIC_QUERY_TERMS = Set.of(
            "citywalk", "拍照", "出片", "摄影", "散步", "漫步", "晚霞", "日落", "夕阳",
            "夜景", "海边", "海滨", "海风", "打卡", "咖啡"
    );

    private static final Map<String, List<String>> QUERY_SYNONYMS = Map.ofEntries(
            // 校园/校区类简称
            Map.entry("中珠", List.of("中山大学珠海校区", "中大珠海")),
            Map.entry("中大珠海", List.of("中山大学珠海校区", "中珠")),
            // 景点/商圈类简称
            Map.entry("海珠湿地", List.of("海珠国家湿地公园")),
            Map.entry("珠城", List.of("珠江新城")),
            Map.entry("花城广场", List.of("珠江新城花城广场", "珠江新城")),
            Map.entry("日月贝", List.of("珠海日月贝游艇海钓出海中心")),
            Map.entry("鸡山市集", List.of("下一站咖啡(鸡山市集)")),
            Map.entry("深圳行政服务大厅", List.of("深圳市行政服务大厅")),
            // 「城市 + 地点」的口语写法,用户常这么搜,但库里存的是不带城市前缀的地点名
            Map.entry("上海外滩", List.of("外滩")),
            Map.entry("广州永庆坊", List.of("永庆坊")),
            Map.entry("广州东山口", List.of("东山口")),
            Map.entry("成都望平街", List.of("望平街")),
            // 风格/场景类同义词
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
            // 锚点至少 3 个字,避免 "附近/散步" 这类短词把不相关帖子也放进来。
            if (normalizedAnchor.length() < 3) {
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
        return extractQueryTerms(queryText, maxVariants);
    }

    private List<String> buildAnchorVariants(String queryText, int maxVariants) {
        return extractQueryTerms(queryText, maxVariants);
    }

    /**
     * 从 query 里抽取关键词项。
     *
     * <p>中文 query 通常没有空格,直接按空格切分会把整句话当成一个关键词去做 LIKE 匹配,永远命中不了。
     * 所以这里在按空格/标点切段之后,再对中文片段做 4→3→2 字 n-gram 抽取(从尾部往前,优先保留更像地点/主题的后缀词),
     * 并过滤掉含疑问词、助词、口语连接词的噪音词项。
     */
    private List<String> extractQueryTerms(String queryText, int maxVariants) {
        String normalizedQuery = defaultText(queryText, "").trim();
        if (normalizedQuery.isBlank()) {
            return List.of();
        }

        Set<String> terms = new LinkedHashSet<>();

        // 已知同义词优先级最高,例如 中珠 / 中大珠海 / 中山大学珠海校区。
        for (Map.Entry<String, List<String>> entry : QUERY_SYNONYMS.entrySet()) {
            if (normalizedQuery.contains(entry.getKey())) {
                addVariant(terms, entry.getKey());
                for (String synonym : entry.getValue()) {
                    addVariant(terms, synonym);
                }
            }
        }

        String cleaned = normalizedQuery.replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", " ").trim();
        for (String segment : cleaned.split("\\s+")) {
            addVariant(terms, segment);
            for (String gram : extractNgrams(segment)) {
                addVariant(terms, gram);
            }
        }

        return terms.stream()
                .limit(Math.max(1, maxVariants))
                .toList();
    }

    private List<String> extractNgrams(String segment) {
        List<String> grams = new ArrayList<>();
        String normalized = defaultText(segment, "").trim();
        if (normalized.length() < 2 || !containsCjk(normalized)) {
            return grams;
        }
        // 只做 4 字 / 3 字切片:2 字切片(likely 通用词)命中率低、噪音大,反而会污染 RRF 融合。
        for (int length = 4; length >= 3; length--) {
            int added = 0;
            for (int end = normalized.length(); end - length >= 0 && added < MAX_GRAMS_PER_LENGTH; end--) {
                String gram = normalized.substring(end - length, end);
                if (!isUsefulTerm(gram)) {
                    continue;
                }
                grams.add(gram);
                added++;
            }
        }
        return grams;
    }

    private boolean containsCjk(String value) {
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch >= '\u4e00' && ch <= '\u9fff') {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAnyAnchor(CommunityWalkQueryRow row, List<String> anchorVariants) {
        if (anchorVariants == null || anchorVariants.isEmpty()) {
            return true;
        }
        String title = normalizeText(row.getThemeTitle());
        String location = normalizeText(row.getLocationName());
        String tags = normalizeText(row.getTags());
        for (String anchor : anchorVariants) {
            String normalizedAnchor = normalizeText(anchor);
            if (normalizedAnchor.length() < 2) {
                continue;
            }
            // 锚点只认标题/地点/标签这类强字段:备注、主题描述里出现某个词往往只是顺带提及,用来做召回会放大噪音。
            if (title.contains(normalizedAnchor)
                    || location.contains(normalizedAnchor)
                    || tags.contains(normalizedAnchor)) {
                return true;
            }
        }
        return false;
    }

    private void addVariant(Set<String> variants, String candidate) {
        String normalized = defaultText(candidate, "").trim();
        if (isUsefulTerm(normalized)) {
            variants.add(normalized);
        }
    }

    private boolean isUsefulTerm(String term) {
        String normalized = defaultText(term, "").trim();
        if (normalized.length() < 2 || normalized.length() > 8) {
            return false;
        }
        if (GENERIC_QUERY_TERMS.contains(normalized.toLowerCase(Locale.ROOT))) {
            return false;
        }
        for (int index = 0; index < normalized.length(); index++) {
            if (QUERY_STOP_CHARS.contains(normalized.charAt(index))) {
                return false;
            }
        }
        return true;
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
