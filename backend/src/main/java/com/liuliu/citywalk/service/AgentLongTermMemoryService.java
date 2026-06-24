package com.liuliu.citywalk.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.mapper.AgentUserMemoryMapper;
import com.liuliu.citywalk.mapper.entity.AgentUserMemoryEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AgentLongTermMemoryService {

    private static final Logger log = LoggerFactory.getLogger(AgentLongTermMemoryService.class);

    private static final List<String> STYLE_KEYWORDS = List.of(
            "日落", "拍照", "咖啡", "校园", "老街", "文艺", "历史",
            "夜景", "海边", "自然", "书店", "美食", "亲子", "轻松"
    );

    private static final List<String> AVOID_KEYWORDS = List.of(
            "人多", "排队", "商业化", "太晒", "暴走", "爬坡", "室内", "吵", "远"
    );

    private static final List<String> AREA_NOISE_KEYWORDS = List.of(
            "推荐", "漫步", "路线", "周边", "附近", "出发", "位置", "今天", "适合", "喜欢", "建议"
    );

    private static final List<String> CITY_NAMES = List.of(
            "上海", "北京", "广州", "深圳", "杭州", "苏州", "南京", "武汉", "成都", "重庆",
            "西安", "长沙", "青岛", "厦门", "福州", "天津", "珠海", "佛山", "东莞", "宁波",
            "无锡", "昆明", "大连", "郑州", "济南", "合肥", "南昌", "南宁", "贵阳", "海口",
            "三亚", "洛阳", "开封", "扬州", "绍兴", "沈阳", "长春", "哈尔滨", "太原", "兰州"
    );

    private static final Pattern DURATION_PATTERN = Pattern.compile(
            "(半小时|半天|一天|两天|一小时|两小时|三小时|四小时|五小时|六小时|一个晚上|一整晚|\\d+(?:\\.\\d+)?\\s*(?:小时|h|H))"
    );
    private static final Pattern AREA_PATTERN = Pattern.compile("([\\u4e00-\\u9fa5A-Za-z0-9]{2,20}(?:路|街|街区|校区|公园|商圈|古镇|湖|湾|岛|区|镇|村))");

    private final AgentUserMemoryMapper agentUserMemoryMapper;
    private final ObjectMapper objectMapper;

    public AgentLongTermMemoryService(AgentUserMemoryMapper agentUserMemoryMapper, ObjectMapper objectMapper) {
        this.agentUserMemoryMapper = agentUserMemoryMapper;
        this.objectMapper = objectMapper;
    }

    public String buildPromptContext(Long userId) {
        AgentLongTermMemoryProfile profile = loadProfile(userId);
        if (profile.isEmpty()) {
            return "";
        }

        List<String> lines = new ArrayList<>();
        lines.add("以下是这个用户的长期偏好记忆，如果和本轮明确新需求冲突，以本轮需求为准：");
        if (!profile.preferredCities().isEmpty()) {
            lines.add("- 常去/偏好城市：" + String.join("、", profile.preferredCities()));
        }
        if (!profile.preferredAreas().isEmpty()) {
            lines.add("- 常提及区域：" + String.join("、", profile.preferredAreas()));
        }
        if (!profile.walkStyles().isEmpty()) {
            lines.add("- 偏好风格：" + String.join("、", profile.walkStyles()));
        }
        if (!profile.preferredDuration().isBlank()) {
            lines.add("- 偏好时长：" + profile.preferredDuration());
        }
        if (!profile.mobilityLevel().isBlank()) {
            lines.add("- 体力/节奏偏好：" + profile.mobilityLevel());
        }
        if (!profile.avoidTags().isEmpty()) {
            lines.add("- 避雷点：" + String.join("、", profile.avoidTags()));
        }
        if (!profile.recentSuggestedAreas().isEmpty()) {
            lines.add("- 最近给过的推荐区域：" + String.join("、", profile.recentSuggestedAreas()));
        }
        if (!profile.summary().isBlank()) {
            lines.add("- 记忆摘要：" + profile.summary());
        }
        return "\n\n" + String.join("\n", lines);
    }

    public void rememberTurn(Long userId, String userPrompt, String assistantAnswer) {
        if (userId == null || userId <= 0) {
            return;
        }

        try {
            AgentLongTermMemoryProfile existing = loadProfile(userId);
            AgentLongTermMemoryProfile extracted = extractProfile(userPrompt, assistantAnswer);
            AgentLongTermMemoryProfile merged = merge(existing, extracted);
            if (merged.isEmpty()) {
                return;
            }
            agentUserMemoryMapper.upsertMemory(
                    userId,
                    writeList(merged.preferredCities()),
                    writeList(merged.preferredAreas()),
                    writeList(merged.walkStyles()),
                    blankToNull(merged.preferredDuration()),
                    blankToNull(merged.mobilityLevel()),
                    writeList(merged.avoidTags()),
                    writeList(merged.recentSuggestedAreas()),
                    blankToNull(merged.summary())
            );
        } catch (Exception error) {
            log.warn("Agent long-term memory update failed for userId={}: {}", userId, error.getMessage());
        }
    }

    private AgentLongTermMemoryProfile loadProfile(Long userId) {
        if (userId == null || userId <= 0) {
            return AgentLongTermMemoryProfile.empty();
        }
        AgentUserMemoryEntity entity = agentUserMemoryMapper.findByUserId(userId);
        if (entity == null) {
            return AgentLongTermMemoryProfile.empty();
        }
        return new AgentLongTermMemoryProfile(
                readList(entity.getPreferredCities()),
                readList(entity.getPreferredAreas()),
                readList(entity.getWalkStyles()),
                normalize(entity.getPreferredDuration()),
                normalize(entity.getMobilityLevel()),
                readList(entity.getAvoidTags()),
                readList(entity.getRecentSuggestedAreas()),
                normalize(entity.getSummary())
        );
    }

    private AgentLongTermMemoryProfile extractProfile(String userPrompt, String assistantAnswer) {
        String prompt = normalize(userPrompt);
        String answer = normalize(assistantAnswer);

        List<String> promptAreas = extractAreas(prompt);
        List<String> recentAreas = extractAreas(answer);
        List<String> areas = promptAreas.isEmpty() ? trimList(recentAreas, 3) : promptAreas;
        List<String> cities = mergeList(
                extractCities(prompt),
                mergeList(extractCities(answer), extractCitiesFromAreas(mergeList(promptAreas, recentAreas, 6)), 4),
                4
        );
        List<String> styles = extractStyles(prompt);
        String duration = extractDuration(prompt);
        String mobility = extractMobilityLevel(prompt);
        List<String> avoidTags = extractAvoidTags(prompt);
        String summary = buildSummary(cities, areas, styles, duration, mobility, avoidTags, recentAreas);

        return new AgentLongTermMemoryProfile(
                cities,
                areas,
                styles,
                duration,
                mobility,
                avoidTags,
                trimList(recentAreas, 4),
                summary
        );
    }

    private AgentLongTermMemoryProfile merge(AgentLongTermMemoryProfile existing, AgentLongTermMemoryProfile extracted) {
        List<String> preferredCities = mergeList(existing.preferredCities(), extracted.preferredCities(), 6);
        List<String> preferredAreas = mergeList(existing.preferredAreas(), extracted.preferredAreas(), 8);
        List<String> walkStyles = mergeList(existing.walkStyles(), extracted.walkStyles(), 8);
        String preferredDuration = chooseLatestNonBlank(existing.preferredDuration(), extracted.preferredDuration());
        String mobilityLevel = chooseLatestNonBlank(existing.mobilityLevel(), extracted.mobilityLevel());
        List<String> avoidTags = mergeList(existing.avoidTags(), extracted.avoidTags(), 8);
        List<String> recentSuggestedAreas = mergeList(extracted.recentSuggestedAreas(), existing.recentSuggestedAreas(), 6);
        String summary = buildSummary(
                preferredCities,
                preferredAreas,
                walkStyles,
                preferredDuration,
                mobilityLevel,
                avoidTags,
                recentSuggestedAreas
        );
        return new AgentLongTermMemoryProfile(
                preferredCities,
                preferredAreas,
                walkStyles,
                preferredDuration,
                mobilityLevel,
                avoidTags,
                recentSuggestedAreas,
                summary
        );
    }

    private List<String> extractCities(String text) {
        List<String> hits = new ArrayList<>();
        for (String city : CITY_NAMES) {
            if (text.contains(city)) {
                hits.add(city);
            }
        }
        return trimList(hits, 4);
    }

    private List<String> extractAreas(String text) {
        Matcher matcher = AREA_PATTERN.matcher(text);
        List<String> hits = new ArrayList<>();
        while (matcher.find()) {
            String value = normalize(matcher.group(1));
            if (value.length() < 2 || value.length() > 20) {
                continue;
            }
            if (isNoisyArea(value)) {
                continue;
            }
            hits.add(value);
        }
        return trimList(hits, 6);
    }

    private List<String> extractCitiesFromAreas(List<String> areas) {
        List<String> hits = new ArrayList<>();
        if (areas == null || areas.isEmpty()) {
            return hits;
        }
        for (String area : areas) {
            for (String city : CITY_NAMES) {
                if (area.contains(city)) {
                    hits.add(city);
                }
            }
        }
        return trimList(hits, 4);
    }

    private List<String> extractStyles(String text) {
        List<String> hits = new ArrayList<>();
        for (String keyword : STYLE_KEYWORDS) {
            if (text.contains(keyword)) {
                hits.add(keyword);
            }
        }
        if (text.contains("低体力") || text.contains("不想走太多") || text.contains("轻松")) {
            hits.add("轻松");
        }
        return trimList(hits, 6);
    }

    private String extractDuration(String text) {
        Matcher matcher = DURATION_PATTERN.matcher(text);
        return matcher.find() ? normalize(matcher.group(1)) : "";
    }

    private String extractMobilityLevel(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        if (normalized.contains("低体力") || normalized.contains("轻松") || normalized.contains("不想走太多")) {
            return "轻松低体力";
        }
        if (normalized.contains("暴走") || normalized.contains("高强度") || normalized.contains("能走")) {
            return "高强度";
        }
        return "";
    }

    private List<String> extractAvoidTags(String text) {
        List<String> hits = new ArrayList<>();
        for (String keyword : AVOID_KEYWORDS) {
            if ((text.contains("不要" + keyword) || text.contains("不想" + keyword) || text.contains("避开" + keyword))
                    || (text.contains("不要") && text.contains(keyword))
                    || (text.contains("避开") && text.contains(keyword))) {
                hits.add(keyword);
            }
        }
        return trimList(hits, 6);
    }

    private String buildSummary(
            List<String> cities,
            List<String> areas,
            List<String> styles,
            String duration,
            String mobility,
            List<String> avoidTags,
            List<String> recentAreas
    ) {
        List<String> parts = new ArrayList<>();
        if (!cities.isEmpty()) {
            parts.add("偏好城市：" + String.join("、", cities));
        }
        if (!areas.isEmpty()) {
            parts.add("常提区域：" + String.join("、", areas));
        }
        if (!styles.isEmpty()) {
            parts.add("路线风格：" + String.join("、", styles));
        }
        if (!duration.isBlank()) {
            parts.add("时长：" + duration);
        }
        if (!mobility.isBlank()) {
            parts.add("体力偏好：" + mobility);
        }
        if (!avoidTags.isEmpty()) {
            parts.add("避雷：" + String.join("、", avoidTags));
        }
        if (parts.isEmpty() && recentAreas != null && !recentAreas.isEmpty()) {
            parts.add("最近关注区域：" + String.join("、", trimList(recentAreas, 3)));
        }
        return String.join("；", parts);
    }

    private boolean isNoisyArea(String value) {
        for (String keyword : AREA_NOISE_KEYWORDS) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private List<String> readList(String json) {
        String normalized = normalize(json);
        if (normalized.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(normalized, new TypeReference<List<String>>() {
            });
            return trimList(values, 8);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String writeList(List<String> values) {
        List<String> normalized = trimList(values, 8);
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception error) {
            return null;
        }
    }

    private List<String> mergeList(List<String> primary, List<String> secondary, int maxSize) {
        Set<String> values = new LinkedHashSet<>();
        addNormalized(values, primary);
        addNormalized(values, secondary);
        return trimList(new ArrayList<>(values), maxSize);
    }

    private void addNormalized(Set<String> target, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isBlank()) {
                target.add(normalized);
            }
        }
    }

    private List<String> trimList(List<String> values, int maxSize) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String item = normalize(value);
            if (item.isBlank()) {
                continue;
            }
            normalized.add(item);
            if (normalized.size() >= Math.max(1, maxSize)) {
                break;
            }
        }
        return new ArrayList<>(normalized);
    }

    private String chooseLatestNonBlank(String currentValue, String nextValue) {
        String normalizedNext = normalize(nextValue);
        return normalizedNext.isBlank() ? normalize(currentValue) : normalizedNext;
    }

    private String blankToNull(String value) {
        String normalized = normalize(value);
        return normalized.isBlank() ? null : normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public record AgentLongTermMemoryProfile(
            List<String> preferredCities,
            List<String> preferredAreas,
            List<String> walkStyles,
            String preferredDuration,
            String mobilityLevel,
            List<String> avoidTags,
            List<String> recentSuggestedAreas,
            String summary
    ) {
        static AgentLongTermMemoryProfile empty() {
            return new AgentLongTermMemoryProfile(List.of(), List.of(), List.of(), "", "", List.of(), List.of(), "");
        }

        boolean isEmpty() {
            return preferredCities.isEmpty()
                    && preferredAreas.isEmpty()
                    && walkStyles.isEmpty()
                    && preferredDuration.isBlank()
                    && mobilityLevel.isBlank()
                    && avoidTags.isEmpty()
                    && recentSuggestedAreas.isEmpty()
                    && summary.isBlank();
        }
    }
}
