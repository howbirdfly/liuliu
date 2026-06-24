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

    private static final int DEFAULT_RESULT_LIMIT = 4;
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
            Map<String, Object> payload = objectMapper.readValue(normalizedOutput, new TypeReference<Map<String, Object>>() {
            });
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

        List<Map<String, Object>> slicedResults = new ArrayList<>();
        for (Object item : toList(payload.get("results"), DEFAULT_RESULT_LIMIT)) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            putIfNotBlank(entry, "title", firstText(raw, "title", "name"));
            putIfNotBlank(entry, "area", metadataText(raw, "location_name", "area", "district"));
            putIfNotBlank(entry, "tags", metadataText(raw, "tags"));
            putIfNotBlank(entry, "summary", trim(firstText(raw, "content", "summary", "description"), DEFAULT_TEXT_LIMIT));
            putIfNotBlank(entry, "sourceType", text(raw.get("sourceType")));
            putIfPresent(entry, "score", roundScore(raw.get("score")));
            if (!entry.isEmpty()) {
                slicedResults.add(entry);
            }
        }
        result.put("resultCount", slicedResults.size());
        result.put("results", slicedResults);
        result.put("guidance", "优先吸收这些真实参考，再决定是否继续补地图点位。");
        return result;
    }

    private Map<String, Object> sliceCommunityGuides(Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", payload.getOrDefault("success", true));
        appendIfPresent(payload, result, "keyword");

        List<Map<String, Object>> slicedResults = new ArrayList<>();
        for (Object item : toList(payload.get("results"), 3)) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            putIfNotBlank(entry, "title", firstText(raw, "title", "themeTitle", "locationName", "name"));
            putIfNotBlank(entry, "area", firstText(raw, "locationName", "area", "city"));
            putIfNotBlank(entry, "summary", trim(firstText(raw, "story", "shortNote", "summary", "content"), DEFAULT_TEXT_LIMIT));
            putIfNotBlank(entry, "author", firstText(raw, "authorNickname", "nickname"));
            if (!entry.isEmpty()) {
                slicedResults.add(entry);
            }
        }
        result.put("resultCount", slicedResults.size());
        result.put("results", slicedResults);
        result.put("guidance", "把这些公开路线当作灵感参考，不要逐字复述。");
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
            putIfNotBlank(detail, "title", firstText(raw, "title", "themeTitle", "locationName"));
            putIfNotBlank(detail, "area", firstText(raw, "locationName", "area", "city"));
            putIfNotBlank(detail, "summary", trim(firstText(raw, "story", "shortNote", "content"), DEFAULT_TEXT_LIMIT));
            Object missions = raw.get("missions");
            List<String> missionPreview = previewScalarList(missions, 3);
            if (!missionPreview.isEmpty()) {
                detail.put("missions", missionPreview);
            }
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
            putIfPresent(entry, "lat", raw.get("lat"));
            putIfPresent(entry, "lng", raw.get("lng"));
            putIfNotBlank(entry, "link", firstText(raw, "amapUrl", "link", "url"));
            if (!entry.isEmpty()) {
                slicedResults.add(entry);
            }
        }
        result.put("resultCount", slicedResults.size());
        result.put("results", slicedResults);
        result.put("guidance", "只把这些点位当候选，不要默认每个都必须进路线。");
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

    private List<?> toList(Object value, int limit) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        return list.size() <= limit ? list : list.subList(0, limit);
    }

    private List<String> previewScalarList(Object value, int limit) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            String text = trim(text(item), 60);
            if (!text.isBlank()) {
                result.add(text);
            }
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
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
            String text = text(value);
            if (!text.isBlank()) {
                return text;
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
            String text = text(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private Object roundScore(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        double raw = number.doubleValue();
        return Math.round(raw * 1000.0) / 1000.0;
    }

    private String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof List<?> list) {
            List<String> items = new ArrayList<>();
            for (Object item : list) {
                String text = normalize(String.valueOf(item));
                if (!text.isBlank()) {
                    items.add(text);
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
