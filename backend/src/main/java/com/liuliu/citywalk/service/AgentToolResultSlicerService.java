package com.liuliu.citywalk.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AgentToolResultSlicerService {

    private static final int DEFAULT_TEXT_LIMIT = 120;

    private final ObjectMapper objectMapper;

    public AgentToolResultSlicerService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String sliceForModel(String toolName, String output) {
        String normalizedToolName = normalize(toolName);
        String normalizedOutput = normalize(output);
        if (normalizedOutput.isBlank()) {
            return "";
        }

        try {
            Map<String, Object> payload = objectMapper.readValue(
                    normalizedOutput,
                    new TypeReference<Map<String, Object>>() {
                    }
            );
            Map<String, Object> sliced = switch (normalizedToolName) {
                case "search_knowledge_base" -> sliceKnowledgeBase(payload);
                case "search_community_guides" -> sliceCommunityGuides(payload);
                case "get_walk_detail" -> sliceWalkDetail(payload);
                case "search_poi", "nearby_pois" -> slicePoiResults(payload);
                default -> sliceGeneric(payload);
            };
            return objectMapper.writeValueAsString(sliced);
        } catch (Exception ignored) {
            return trim(normalizedOutput, 480);
        }
    }

    private Map<String, Object> sliceKnowledgeBase(Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", payload.getOrDefault("success", true));
        appendIfPresent(payload, result, "query");
        appendIfPresent(payload, result, "sourceType");
        appendIfPresent(payload, result, "topK");

        List<Map<String, Object>> slicedResults = new ArrayList<>();
        for (Object item : toList(payload.get("results"), 4)) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }

            String title = firstText(raw, "title", "name");
            String area = firstText(raw, "locationName", "area", "city");
            if (area.isBlank()) {
                area = metadataText(raw, "location_name", "area", "district");
            }
            String summary = trim(firstText(raw, "content", "summary", "description"), DEFAULT_TEXT_LIMIT);
            String sourceType = text(raw.get("sourceType"));
            List<String> tags = toStringList(firstNonNull(raw.get("tags"), metadataValue(raw, "tags")), 4, 20);
            String author = firstText(raw, "authorNickname", "nickname");
            if (author.isBlank()) {
                author = metadataText(raw, "author_nickname");
            }
            String recallSource = metadataText(raw, "recall_source");
            String keywordMatch = metadataText(raw, "keyword_match");
            String createdAt = metadataText(raw, "created_at");

            Map<String, Object> entry = new LinkedHashMap<>();
            putIfNotBlank(entry, "sourceId", text(raw.get("sourceId")));
            putIfNotBlank(entry, "sourceType", sourceType);
            putIfNotBlank(entry, "title", title);
            putIfNotBlank(entry, "area", area);
            putIfNotBlank(entry, "author", author);
            if (!tags.isEmpty()) {
                entry.put("tags", tags);
            }
            putIfNotBlank(entry, "summary", summary);
            putIfPresent(entry, "score", roundScore(raw.get("score")));
            putIfNotBlank(entry, "matchSignal", buildKnowledgeMatchSignal(keywordMatch, recallSource, tags, area));
            putIfNotBlank(entry, "routeSignal", buildKnowledgeRouteSignal(sourceType, title, summary, tags));
            putIfNotBlank(entry, "freshness", buildFreshness(createdAt));
            putIfNotBlank(entry, "createdAt", createdAt);
            if (!entry.isEmpty()) {
                slicedResults.add(entry);
            }
        }

        result.put("resultCount", slicedResults.size());
        result.put("results", slicedResults);
        result.put("guidance", "Treat these as evidence snippets. Reuse the facts and signals, not the raw wording.");
        return result;
    }

    private Map<String, Object> sliceCommunityGuides(Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", payload.getOrDefault("success", true));
        appendIfPresent(payload, result, "keyword");
        appendIfPresent(payload, result, "page");
        appendIfPresent(payload, result, "pageSize");

        String keyword = text(payload.get("keyword"));
        List<Map<String, Object>> slicedResults = new ArrayList<>();
        for (Object item : toList(payload.get("results"), 3)) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }

            String title = firstText(raw, "themeTitle", "title", "locationName", "name");
            String themeCategory = firstText(raw, "themeCategory");
            String area = firstText(raw, "locationName", "area", "city");
            String summary = trim(firstText(raw, "noteText", "story", "shortNote", "summary", "content"), DEFAULT_TEXT_LIMIT);
            String author = firstText(raw, "authorNickname", "nickname");
            String recordUnit = firstText(raw, "recordUnit");
            List<String> tags = toStringList(raw.get("tags"), 5, 16);
            List<String> missionPreview = previewNamedList(raw.get("completedMissions"), 3, 24);
            int pathPointCount = sizeOfList(raw.get("path"));

            Map<String, Object> entry = new LinkedHashMap<>();
            putIfPresent(entry, "id", raw.get("id"));
            putIfNotBlank(entry, "title", title);
            putIfNotBlank(entry, "themeCategory", themeCategory);
            putIfNotBlank(entry, "area", area);
            putIfNotBlank(entry, "summary", summary);
            putIfNotBlank(entry, "author", author);
            putIfNotBlank(entry, "recordUnit", recordUnit);
            if (!tags.isEmpty()) {
                entry.put("tags", tags);
            }
            if (!missionPreview.isEmpty()) {
                entry.put("missionPreview", missionPreview);
            }
            if (pathPointCount > 0) {
                entry.put("pathPointCount", pathPointCount);
            }

            Map<String, Object> popularity = buildPopularity(raw);
            if (!popularity.isEmpty()) {
                entry.put("popularity", popularity);
            }

            putIfNotBlank(
                    entry,
                    "fitFor",
                    buildCommunityFitFor(title, themeCategory, recordUnit, tags, summary)
            );
            putIfNotBlank(
                    entry,
                    "whyRelevant",
                    buildCommunityWhyRelevant(keyword, title, area, themeCategory, tags, summary, missionPreview, pathPointCount)
            );
            putIfNotBlank(
                    entry,
                    "routeSignal",
                    buildRouteSignal(recordUnit, missionPreview, pathPointCount)
            );
            putIfPresent(entry, "createdAt", raw.get("createdAt"));

            if (!entry.isEmpty()) {
                slicedResults.add(entry);
            }
        }

        result.put("resultCount", slicedResults.size());
        result.put("results", slicedResults);
        result.put("guidance", "Use these public walks as grounded inspiration, not as text to quote verbatim.");
        return result;
    }

    private Map<String, Object> sliceWalkDetail(Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", payload.getOrDefault("success", true));
        result.put("found", payload.getOrDefault("found", false));
        appendIfPresent(payload, result, "walkId");

        Object rawResult = payload.get("result");
        if (rawResult instanceof Map<?, ?> raw) {
            Map<String, Object> detail = new LinkedHashMap<>();
            String title = firstText(raw, "themeTitle", "title", "locationName");
            String themeCategory = firstText(raw, "themeCategory");
            String area = firstText(raw, "locationName", "area", "city");
            String summary = trim(firstText(raw, "noteText", "story", "shortNote", "content"), DEFAULT_TEXT_LIMIT);
            List<String> tags = toStringList(raw.get("tags"), 5, 18);
            List<String> missionPreview = previewNamedList(raw.get("completedMissions"), 4, 28);
            int pathPointCount = sizeOfList(raw.get("path"));

            putIfNotBlank(detail, "title", title);
            putIfNotBlank(detail, "themeCategory", themeCategory);
            putIfNotBlank(detail, "area", area);
            putIfNotBlank(detail, "summary", summary);
            if (!tags.isEmpty()) {
                detail.put("tags", tags);
            }
            if (!missionPreview.isEmpty()) {
                detail.put("missions", missionPreview);
            }
            if (pathPointCount > 0) {
                detail.put("pathPointCount", pathPointCount);
            }

            Map<String, Object> popularity = buildPopularity(raw);
            if (!popularity.isEmpty()) {
                detail.put("popularity", popularity);
            }

            putIfNotBlank(
                    detail,
                    "routeSignal",
                    buildRouteSignal(firstText(raw, "recordUnit"), missionPreview, pathPointCount)
            );
            putIfPresent(detail, "createdAt", raw.get("createdAt"));
            if (!detail.isEmpty()) {
                result.put("result", detail);
            }
        }
        return result;
    }

    private Map<String, Object> slicePoiResults(Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", payload.getOrDefault("success", true));
        appendIfPresent(payload, result, "query");
        appendIfPresent(payload, result, "message");
        appendIfPresent(payload, result, "error");

        List<Map<String, Object>> slicedResults = new ArrayList<>();
        for (Object item : toList(payload.get("results"), 5)) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            putIfNotBlank(entry, "name", firstText(raw, "name", "title"));
            putIfNotBlank(entry, "address", firstText(raw, "address", "snippet", "district"));
            putIfPresent(entry, "lat", raw.get("lat"));
            putIfPresent(entry, "lng", raw.get("lng"));
            putIfNotBlank(entry, "link", firstText(raw, "amapUrl", "link", "url"));
            if (!entry.isEmpty()) {
                slicedResults.add(entry);
            }
        }

        result.put("resultCount", slicedResults.size());
        result.put("results", slicedResults);
        result.put("guidance", "Treat these places as candidate stops instead of mandatory stops.");
        return result;
    }

    private Map<String, Object> sliceGeneric(Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        appendIfPresent(payload, result, "success");
        appendIfPresent(payload, result, "message");
        appendIfPresent(payload, result, "error");
        appendIfPresent(payload, result, "fallbackSuggestion");
        appendIfPresent(payload, result, "query");

        List<Map<String, Object>> previewResults = new ArrayList<>();
        for (Object item : toList(payload.get("results"), 3)) {
            if (!(item instanceof Map<?, ?> raw)) {
                Map<String, Object> wrapped = new LinkedHashMap<>();
                putIfNotBlank(wrapped, "value", trim(text(item), 80));
                if (!wrapped.isEmpty()) {
                    previewResults.add(wrapped);
                }
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            putIfNotBlank(entry, "title", firstText(raw, "title", "name", "locationName"));
            putIfNotBlank(entry, "summary", trim(firstText(raw, "summary", "description", "content", "story"), 100));
            if (!entry.isEmpty()) {
                previewResults.add(entry);
            }
        }

        if (!previewResults.isEmpty()) {
            result.put("results", previewResults);
        }
        return result;
    }

    private Map<String, Object> buildPopularity(Map<?, ?> raw) {
        Map<String, Object> popularity = new LinkedHashMap<>();
        putIfPresent(popularity, "likes", raw.get("likeCount"));
        putIfPresent(popularity, "favorites", raw.get("favoriteCount"));
        putIfPresent(popularity, "views", raw.get("viewCount"));
        return popularity;
    }

    private String buildKnowledgeMatchSignal(
            String keywordMatch,
            String recallSource,
            List<String> tags,
            String area
    ) {
        List<String> parts = new ArrayList<>();
        if (!normalize(keywordMatch).isBlank()) {
            parts.add("命中关键词 " + normalize(keywordMatch));
        }
        if (!normalize(recallSource).isBlank()) {
            parts.add("召回来源: " + normalize(recallSource));
        }
        if (!tags.isEmpty()) {
            parts.add("带标签线索");
        }
        if (!normalize(area).isBlank()) {
            parts.add("带片区信息");
        }
        return parts.isEmpty() ? "" : String.join("；", trimList(parts, 3));
    }

    private String buildKnowledgeRouteSignal(
            String sourceType,
            String title,
            String summary,
            List<String> tags
    ) {
        String merged = String.join(
                " ",
                normalize(sourceType),
                normalize(title),
                normalize(summary),
                String.join(" ", tags)
        ).toLowerCase(Locale.ROOT);

        List<String> parts = new ArrayList<>();
        if (containsAny(merged, "路线", "walk", "city walk", "citywalk", "散步", "步行")) {
            parts.add("偏路线参考");
        }
        if (containsAny(merged, "拍照", "夜景", "咖啡", "书店", "建筑", "老街")) {
            parts.add("偏主题灵感");
        }
        if (containsAny(merged, "攻略", "建议", "体验", "记录")) {
            parts.add("偏经验总结");
        }
        return parts.isEmpty() ? "" : String.join("；", trimList(parts, 3));
    }

    private String buildFreshness(String createdAt) {
        String normalized = normalize(createdAt);
        if (normalized.isBlank()) {
            return "";
        }
        if (normalized.length() >= 10) {
            return normalized.substring(0, 10);
        }
        return normalized;
    }

    private String buildCommunityFitFor(
            String title,
            String themeCategory,
            String recordUnit,
            List<String> tags,
            String summary
    ) {
        String merged = String.join(
                " ",
                normalize(title),
                normalize(themeCategory),
                normalize(recordUnit),
                String.join(" ", tags),
                normalize(summary)
        ).toLowerCase(Locale.ROOT);

        List<String> fits = new ArrayList<>();
        if (containsAny(merged, "拍照", "出片", "夜景", "日落", "摄影")) {
            fits.add("拍照散步");
        }
        if (containsAny(merged, "咖啡", "书店", "文艺", "慢逛", "安静")) {
            fits.add("慢逛停留");
        }
        if (containsAny(merged, "老街", "建筑", "街区", "历史", "社区")) {
            fits.add("街区观察");
        }
        if (containsAny(merged, "亲子", "动物", "公园", "轻松")) {
            fits.add("轻松陪伴");
        }
        if (containsAny(merged, "美食", "小吃", "餐厅", "吃")) {
            fits.add("边走边吃");
        }
        return fits.isEmpty() ? "路线灵感参考" : String.join("、", trimList(fits, 3));
    }

    private String buildCommunityWhyRelevant(
            String keyword,
            String title,
            String area,
            String themeCategory,
            List<String> tags,
            String summary,
            List<String> missionPreview,
            int pathPointCount
    ) {
        List<String> reasons = new ArrayList<>();
        String merged = String.join(
                " ",
                normalize(title),
                normalize(area),
                normalize(themeCategory),
                String.join(" ", tags),
                normalize(summary),
                String.join(" ", missionPreview)
        ).toLowerCase(Locale.ROOT);

        for (String token : extractKeywordTokens(keyword)) {
            if (!token.isBlank() && merged.contains(token.toLowerCase(Locale.ROOT))) {
                reasons.add("命中关键词 " + token);
            }
            if (reasons.size() >= 2) {
                break;
            }
        }

        if (!normalize(area).isBlank()) {
            reasons.add("带有可落地片区");
        }
        if (!missionPreview.isEmpty()) {
            reasons.add("带有可执行任务");
        }
        if (pathPointCount > 0) {
            reasons.add("带有真实路线轨迹");
        }
        return reasons.isEmpty() ? "与当前需求语义接近，可作为真实帖子参考。" : String.join("；", trimList(reasons, 3));
    }

    private String buildRouteSignal(String recordUnit, List<String> missionPreview, int pathPointCount) {
        List<String> parts = new ArrayList<>();
        if (pathPointCount > 0) {
            parts.add("含 " + pathPointCount + " 个轨迹点");
        }
        if (!missionPreview.isEmpty()) {
            parts.add("含任务线索");
        }
        if (!normalize(recordUnit).isBlank()) {
            parts.add("记录单位: " + normalize(recordUnit));
        }
        return parts.isEmpty() ? "" : String.join("；", parts);
    }

    private List<String> extractKeywordTokens(String keyword) {
        String normalized = normalize(keyword)
                .replace("citywalk", "city walk")
                .replaceAll("[,，、。.!！？/\\\\()（）\\[\\]{}]+", " ");
        if (normalized.isBlank()) {
            return List.of();
        }

        String[] parts = normalized.split("\\s+");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String token = normalize(part);
            if (token.length() < 2) {
                continue;
            }
            result.add(token);
            if (result.size() >= 5) {
                break;
            }
        }
        return result;
    }

    private boolean containsAny(String text, String... candidates) {
        if (text == null || text.isBlank() || candidates == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank() && text.contains(candidate.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private List<?> toList(Object value, int limit) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        return list.size() <= limit ? list : list.subList(0, limit);
    }

    private List<String> toStringList(Object value, int limit, int itemLimit) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        for (Object item : list) {
            String itemText = trim(text(item), itemLimit);
            if (!itemText.isBlank()) {
                result.add(itemText);
            }
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private List<String> previewNamedList(Object value, int limit, int itemLimit) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        for (Object item : list) {
            String preview;
            if (item instanceof Map<?, ?> raw) {
                preview = firstText(raw, "title", "name", "label", "missionTitle", "content", "text");
            } else {
                preview = text(item);
            }

            preview = trim(preview, itemLimit);
            if (!preview.isBlank()) {
                result.add(preview);
            }
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private int sizeOfList(Object value) {
        if (value instanceof List<?> list) {
            return list.size();
        }
        return 0;
    }

    private void appendIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source == null || target == null || key == null || key.isBlank()) {
            return;
        }
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        String normalized = normalize(value);
        if (!normalized.isBlank()) {
            target.put(key, normalized);
        }
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private String firstText(Map<?, ?> raw, String... keys) {
        if (raw == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            Object value = raw.get(key);
            String resolved = text(value);
            if (!resolved.isBlank()) {
                return resolved;
            }
        }
        return "";
    }

    private String metadataText(Map<?, ?> raw, String... keys) {
        if (raw == null) {
            return "";
        }
        Object metadataObj = raw.get("metadata");
        if (!(metadataObj instanceof Map<?, ?> metadata)) {
            return "";
        }
        for (String key : keys) {
            Object value = metadata.get(key);
            String resolved = text(value);
            if (!resolved.isBlank()) {
                return resolved;
            }
        }
        return "";
    }

    private Object metadataValue(Map<?, ?> raw, String key) {
        if (raw == null || key == null || key.isBlank()) {
            return null;
        }
        Object metadataObj = raw.get("metadata");
        if (!(metadataObj instanceof Map<?, ?> metadata)) {
            return null;
        }
        return metadata.get(key);
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private Object roundScore(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        double raw = number.doubleValue();
        return Math.round(raw * 1000.0) / 1000.0;
    }

    private <T> List<T> trimList(List<T> items, int maxSize) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.size() <= maxSize ? items : items.subList(0, maxSize);
    }

    private String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof List<?> list) {
            List<String> items = new ArrayList<>();
            for (Object item : list) {
                String itemText = normalize(String.valueOf(item));
                if (!itemText.isBlank()) {
                    items.add(itemText);
                }
            }
            return String.join("、", items);
        }
        return normalize(String.valueOf(value));
    }

    private String trim(String value, int limit) {
        String normalized = normalize(value);
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, limit - 1)).trim() + "…";
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\u3000', ' ').trim();
    }
}
