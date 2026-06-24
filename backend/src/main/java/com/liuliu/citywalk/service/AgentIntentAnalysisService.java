package com.liuliu.citywalk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.service.agent.LlmMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AgentIntentAnalysisService {

    private static final List<String> CITY_NAMES = List.of(
            "上海", "北京", "广州", "深圳", "杭州", "苏州", "南京", "武汉", "成都", "重庆",
            "西安", "长沙", "青岛", "厦门", "福州", "天津", "珠海", "佛山", "东莞", "宁波",
            "无锡", "昆明", "大连", "郑州", "济南", "合肥", "南昌", "南宁", "贵阳", "海口",
            "三亚", "洛阳", "开封", "扬州", "绍兴", "沈阳", "长春", "哈尔滨", "太原", "兰州", "中山"
    );

    private static final List<String> STYLE_KEYWORDS = List.of(
            "拍照", "出片", "夜景", "日落", "海边", "老街", "街区", "校园", "自然", "公园",
            "咖啡", "书店", "文艺", "安静", "历史", "建筑", "美食", "亲子", "动物", "市集"
    );

    private static final List<String> OBJECTIVE_KEYWORDS = List.of(
            "散步", "拍照", "看展", "觅食", "打卡", "放空", "约会", "遛娃", "观察", "记录"
    );

    private static final List<String> AVOID_KEYWORDS = List.of(
            "人多", "排队", "商业化", "暴走", "爬坡", "太晒", "室内", "吵", "绕路"
    );

    private static final List<String> REQUEST_KEYWORDS = List.of(
            "推荐", "规划", "路线", "route", "city walk", "citywalk", "主题", "散步",
            "walk", "地点", "去哪", "怎么玩", "附近", "适合", "参考", "攻略", "灵感",
            "拍照", "夜景", "老街", "咖啡", "书店", "公园", "帮我", "想找", "想去"
    );

    private static final List<String> ACKNOWLEDGEMENT_ONLY_TEXTS = List.of(
            "好", "好的", "行", "可以", "继续", "嗯", "嗯嗯", "收到", "明白", "知道了",
            "ok", "okay", "yes", "yep"
    );

    private static final Pattern DURATION_PATTERN = Pattern.compile(
            "(半小时|半天|一天|两天|一小时|两小时|三小时|四小时|五小时|六小时|一个晚上|一整晚|\\d+(?:\\.\\d+)?\\s*(?:小时|h|H))"
    );
    private static final Pattern AREA_PATTERN = Pattern.compile(
            "([\\u4e00-\\u9fa5A-Za-z0-9]{2,20}(?:路|街|街区|公园|商圈|广场|码头|滨江|湖|河|山|巷|里|桥|站|校区|校园|园区|古镇|片区))"
    );

    private final ObjectMapper objectMapper;

    public AgentIntentAnalysisService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AgentIntent analyze(String prompt) {
        String normalizedPrompt = normalize(prompt);
        List<String> cities = extractCities(normalizedPrompt);
        List<String> areas = extractAreas(normalizedPrompt);
        List<String> styles = extractKeywords(normalizedPrompt, STYLE_KEYWORDS, 6);
        List<String> objectives = extractKeywords(normalizedPrompt, OBJECTIVE_KEYWORDS, 4);
        List<String> avoidTags = extractAvoidTags(normalizedPrompt);
        String duration = extractDuration(normalizedPrompt);
        String timePreference = extractTimePreference(normalizedPrompt);
        String mobilityPreference = extractMobilityPreference(normalizedPrompt);

        boolean useCurrentLocation = containsAny(normalizedPrompt, "当前定位", "当前位置", "我附近", "离我近", "周边", "附近");
        boolean needsKnowledgeReference = containsAny(normalizedPrompt, "类似", "参考", "别人", "攻略", "社区", "帖子", "同款");
        boolean needsThemeGeneration = containsAny(normalizedPrompt, "主题", "风格", "玩法", "灵感");
        boolean needsRoutePlanning = containsAny(normalizedPrompt, "路线", "怎么走", "规划", "串起来", "先去", "安排", "city walk", "citywalk");
        boolean needsPoiSearch = useCurrentLocation
                || needsRoutePlanning
                || containsAny(normalizedPrompt, "地点", "店", "街区", "公园", "附近有什么", "去哪");
        boolean acknowledgementOnly = isAcknowledgementOnly(normalizedPrompt);
        boolean requestLike = containsAny(normalizedPrompt, REQUEST_KEYWORDS.toArray(String[]::new));
        List<String> missingSlots = extractMissingSlots(cities, areas, styles, objectives, duration, useCurrentLocation);

        return new AgentIntent(
                normalizedPrompt,
                cities,
                areas,
                styles,
                objectives,
                duration,
                timePreference,
                mobilityPreference,
                avoidTags,
                useCurrentLocation,
                needsKnowledgeReference,
                needsPoiSearch,
                needsRoutePlanning,
                needsThemeGeneration,
                acknowledgementOnly,
                requestLike,
                missingSlots
        );
    }

    public String buildPromptContext(AgentIntent intent) {
        if (intent == null || intent.isEmpty()) {
            return "";
        }

        List<String> lines = new ArrayList<>();
        lines.add("以下是本轮用户需求的结构化摘要，如与长期记忆冲突，以本轮需求为准：");
        if (!intent.cities().isEmpty()) {
            lines.add("- 城市: " + String.join("、", intent.cities()));
        }
        if (!intent.areas().isEmpty()) {
            lines.add("- 区域/地标: " + String.join("、", intent.areas()));
        }
        if (!intent.styles().isEmpty()) {
            lines.add("- 风格偏好: " + String.join("、", intent.styles()));
        }
        if (!intent.objectives().isEmpty()) {
            lines.add("- 主要目标: " + String.join("、", intent.objectives()));
        }
        if (!intent.duration().isBlank()) {
            lines.add("- 期望时长: " + intent.duration());
        }
        if (!intent.timePreference().isBlank()) {
            lines.add("- 时间偏好: " + intent.timePreference());
        }
        if (!intent.mobilityPreference().isBlank()) {
            lines.add("- 行走强度: " + intent.mobilityPreference());
        }
        if (!intent.avoidTags().isEmpty()) {
            lines.add("- 避免内容: " + String.join("、", intent.avoidTags()));
        }
        lines.add("- 是否优先使用当前定位: " + (intent.useCurrentLocation() ? "是" : "否"));
        lines.add("- 是否需要真实案例参考: " + (intent.needsKnowledgeReference() ? "是" : "否"));
        lines.add("- 是否需要地点检索: " + (intent.needsPoiSearch() ? "是" : "否"));
        lines.add("- 是否需要路线规划: " + (intent.needsRoutePlanning() ? "是" : "否"));
        lines.add("- 是否需要主题生成: " + (intent.needsThemeGeneration() ? "是" : "否"));
        if (!intent.missingSlots().isEmpty()) {
            lines.add("- 当前缺失信息: " + String.join("、", intent.missingSlots()));
        }
        return "\n\n" + String.join("\n", lines);
    }

    public String toStepOutput(AgentIntent intent) {
        if (intent == null) {
            return "{}";
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(intent);
        } catch (JsonProcessingException ignored) {
            return "{intent_summary=" + intent.summary() + "}";
        }
    }

    public AgentIntent deriveCarryoverIntent(List<LlmMessage> history) {
        if (history == null || history.isEmpty()) {
            return emptyIntent();
        }

        List<String> cities = List.of();
        List<String> areas = List.of();
        List<String> styles = List.of();
        List<String> objectives = List.of();
        String duration = "";
        String timePreference = "";
        String mobilityPreference = "";
        List<String> avoidTags = List.of();
        boolean useCurrentLocation = false;

        for (int index = history.size() - 1; index >= 0; index--) {
            LlmMessage message = history.get(index);
            if (message == null || !"user".equals(message.role())) {
                continue;
            }

            AgentIntent turnIntent = analyze(message.content());
            if (cities.isEmpty() && !turnIntent.cities().isEmpty()) {
                cities = turnIntent.cities();
            }
            if (areas.isEmpty() && !turnIntent.areas().isEmpty()) {
                areas = turnIntent.areas();
            }
            if (styles.isEmpty() && !turnIntent.styles().isEmpty()) {
                styles = turnIntent.styles();
            }
            if (objectives.isEmpty() && !turnIntent.objectives().isEmpty()) {
                objectives = turnIntent.objectives();
            }
            if (duration.isBlank() && !turnIntent.duration().isBlank()) {
                duration = turnIntent.duration();
            }
            if (timePreference.isBlank() && !turnIntent.timePreference().isBlank()) {
                timePreference = turnIntent.timePreference();
            }
            if (mobilityPreference.isBlank() && !turnIntent.mobilityPreference().isBlank()) {
                mobilityPreference = turnIntent.mobilityPreference();
            }
            if (avoidTags.isEmpty() && !turnIntent.avoidTags().isEmpty()) {
                avoidTags = turnIntent.avoidTags();
            }
            if (!useCurrentLocation && turnIntent.useCurrentLocation()) {
                useCurrentLocation = true;
            }

            if ((!cities.isEmpty() || !areas.isEmpty() || useCurrentLocation)
                    && !styles.isEmpty()
                    && !objectives.isEmpty()
                    && !duration.isBlank()
                    && !timePreference.isBlank()
                    && !mobilityPreference.isBlank()
                    && !avoidTags.isEmpty()) {
                break;
            }
        }

        return new AgentIntent(
                "",
                cities,
                areas,
                styles,
                objectives,
                duration,
                timePreference,
                mobilityPreference,
                avoidTags,
                useCurrentLocation,
                false,
                false,
                false,
                false,
                false,
                false,
                List.of()
        );
    }

    public AgentIntent mergeWithCarryover(AgentIntent current, AgentIntent carryover) {
        if (current == null) {
            return carryover == null ? emptyIntent() : carryover;
        }
        if (carryover == null || carryover.isEmpty()) {
            return current;
        }

        List<String> mergedCities = current.cities().isEmpty() ? carryover.cities() : current.cities();
        List<String> mergedAreas = current.areas().isEmpty() ? carryover.areas() : current.areas();
        List<String> mergedStyles = current.styles().isEmpty() ? carryover.styles() : current.styles();
        List<String> mergedObjectives = current.objectives().isEmpty() ? carryover.objectives() : current.objectives();
        String mergedDuration = current.duration().isBlank() ? carryover.duration() : current.duration();
        String mergedTimePreference = current.timePreference().isBlank() ? carryover.timePreference() : current.timePreference();
        String mergedMobilityPreference = current.mobilityPreference().isBlank() ? carryover.mobilityPreference() : current.mobilityPreference();
        List<String> mergedAvoidTags = current.avoidTags().isEmpty() ? carryover.avoidTags() : current.avoidTags();
        boolean mergedUseCurrentLocation = current.useCurrentLocation() || (current.missingLocationContext() && carryover.useCurrentLocation());

        return new AgentIntent(
                current.prompt(),
                mergedCities,
                mergedAreas,
                mergedStyles,
                mergedObjectives,
                mergedDuration,
                mergedTimePreference,
                mergedMobilityPreference,
                mergedAvoidTags,
                mergedUseCurrentLocation,
                current.needsKnowledgeReference(),
                current.needsPoiSearch(),
                current.needsRoutePlanning(),
                current.needsThemeGeneration(),
                current.acknowledgementOnly(),
                current.requestLike(),
                extractMissingSlots(
                        mergedCities,
                        mergedAreas,
                        mergedStyles,
                        mergedObjectives,
                        mergedDuration,
                        mergedUseCurrentLocation
                )
        );
    }

    public String buildCarryoverPromptContext(AgentIntent current, AgentIntent carryover, AgentIntent effective) {
        if (current == null || carryover == null || effective == null) {
            return "";
        }

        List<String> lines = new ArrayList<>();
        if (current.missingLocationContext() && !effective.missingLocationContext()) {
            if (effective.useCurrentLocation()) {
                lines.add("- Continue using the current location context from earlier turns unless the user changes it.");
            } else if (!effective.cities().isEmpty() || !effective.areas().isEmpty()) {
                List<String> locationParts = new ArrayList<>();
                if (!effective.cities().isEmpty()) {
                    locationParts.add("city=" + String.join(" / ", effective.cities()));
                }
                if (!effective.areas().isEmpty()) {
                    locationParts.add("area=" + String.join(" / ", effective.areas()));
                }
                lines.add("- Carry forward recent conversation location: " + String.join(", ", locationParts) + ".");
            }
        }
        if (current.missingThemeDirection() && !effective.missingThemeDirection()) {
            List<String> themeParts = new ArrayList<>();
            if (!effective.styles().isEmpty()) {
                themeParts.add("style=" + String.join(" / ", effective.styles()));
            }
            if (!effective.objectives().isEmpty()) {
                themeParts.add("goal=" + String.join(" / ", effective.objectives()));
            }
            if (!themeParts.isEmpty()) {
                lines.add("- Keep the recent theme direction unless the user explicitly changes it: " + String.join(", ", themeParts) + ".");
            }
        }
        if (current.missingDuration() && !effective.missingDuration()) {
            lines.add("- Reuse the recent duration preference for this turn: duration=" + effective.duration() + ".");
        }
        if (lines.isEmpty()) {
            return "";
        }

        lines.add(0, "Carry-over context from earlier turns in the same conversation:");
        return String.join("\n", lines);
    }

    private AgentIntent emptyIntent() {
        return new AgentIntent(
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                "",
                "",
                List.of(),
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                List.of()
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
            hits.add(value);
        }
        return trimList(hits, 6);
    }

    private List<String> extractKeywords(String text, List<String> keywords, int limit) {
        List<String> hits = new ArrayList<>();
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                hits.add(keyword);
            }
        }
        return trimList(hits, limit);
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

    private String extractDuration(String text) {
        Matcher matcher = DURATION_PATTERN.matcher(text);
        return matcher.find() ? normalize(matcher.group(1)) : "";
    }

    private String extractTimePreference(String text) {
        if (containsAny(text, "早上", "清晨", "上午")) {
            return "上午";
        }
        if (containsAny(text, "中午", "午后", "下午")) {
            return "下午";
        }
        if (containsAny(text, "傍晚", "黄昏", "日落")) {
            return "傍晚";
        }
        if (containsAny(text, "晚上", "夜景", "夜里")) {
            return "晚上";
        }
        if (containsAny(text, "周末", "周六", "周日")) {
            return "周末";
        }
        return "";
    }

    private String extractMobilityPreference(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (containsAny(text, "轻松", "慢慢走", "不想走太多", "低体力") || lower.contains("easy")) {
            return "轻松";
        }
        if (containsAny(text, "暴走", "能走", "多逛", "高强度")) {
            return "高强度";
        }
        return "";
    }

    private List<String> extractMissingSlots(
            List<String> cities,
            List<String> areas,
            List<String> styles,
            List<String> objectives,
            String duration,
            boolean useCurrentLocation
    ) {
        List<String> missing = new ArrayList<>();
        if (cities.isEmpty() && areas.isEmpty() && !useCurrentLocation) {
            missing.add("城市或区域");
        }
        if (styles.isEmpty() && objectives.isEmpty()) {
            missing.add("主题风格或目标");
        }
        if (duration.isBlank()) {
            missing.add("预计时长");
        }
        return missing;
    }

    private boolean containsAny(String text, String... candidates) {
        if (text == null || text.isBlank() || candidates == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank() && text.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAcknowledgementOnly(String text) {
        String normalized = normalize(text).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return false;
        }
        for (String candidate : ACKNOWLEDGEMENT_ONLY_TEXTS) {
            if (normalized.equals(candidate)) {
                return true;
            }
        }
        return normalized.matches("^[\\p{IsHan}a-zA-Z0-9\\s!！,.，~～?？]+$")
                && normalized.length() <= 6
                && !containsAny(normalized, REQUEST_KEYWORDS.toArray(String[]::new));
    }

    private List<String> trimList(List<String> values, int limit) {
        Set<String> deduplicated = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String normalized = normalize(value);
                if (!normalized.isBlank()) {
                    deduplicated.add(normalized);
                }
            }
        }
        List<String> result = new ArrayList<>(deduplicated);
        return result.size() <= limit ? result : result.subList(0, limit);
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\u3000', ' ').trim();
    }

    public record AgentIntent(
            String prompt,
            List<String> cities,
            List<String> areas,
            List<String> styles,
            List<String> objectives,
            String duration,
            String timePreference,
            String mobilityPreference,
            List<String> avoidTags,
            boolean useCurrentLocation,
            boolean needsKnowledgeReference,
            boolean needsPoiSearch,
            boolean needsRoutePlanning,
            boolean needsThemeGeneration,
            boolean acknowledgementOnly,
            boolean requestLike,
            List<String> missingSlots
    ) {
        public boolean isEmpty() {
            return prompt == null || prompt.isBlank();
        }

        public boolean missingLocationContext() {
            return cities.isEmpty() && areas.isEmpty() && !useCurrentLocation;
        }

        public boolean missingThemeDirection() {
            return styles.isEmpty() && objectives.isEmpty();
        }

        public boolean missingDuration() {
            return duration == null || duration.isBlank();
        }

        public boolean hasMeaningfulPlanningSignal() {
            return !cities.isEmpty()
                    || !areas.isEmpty()
                    || !styles.isEmpty()
                    || !objectives.isEmpty()
                    || (duration != null && !duration.isBlank())
                    || (timePreference != null && !timePreference.isBlank())
                    || (mobilityPreference != null && !mobilityPreference.isBlank())
                    || !avoidTags.isEmpty()
                    || useCurrentLocation
                    || needsKnowledgeReference
                    || needsPoiSearch
                    || needsRoutePlanning
                    || needsThemeGeneration
                    || requestLike;
        }

        public boolean requiresValidInputPrompt() {
            if (isEmpty()) {
                return true;
            }
            if (acknowledgementOnly) {
                return true;
            }
            return !hasMeaningfulPlanningSignal();
        }

        public boolean requiresClarification() {
            if (requiresValidInputPrompt()) {
                return false;
            }
            if (needsRoutePlanning || needsPoiSearch) {
                if (missingLocationContext()) {
                    return true;
                }
                return missingThemeDirection() && missingDuration();
            }
            if (needsThemeGeneration) {
                return missingLocationContext() && missingThemeDirection();
            }
            return false;
        }

        public String summary() {
            List<String> parts = new ArrayList<>();
            if (!cities.isEmpty()) {
                parts.add("城市=" + String.join("、", cities));
            }
            if (!areas.isEmpty()) {
                parts.add("区域=" + String.join("、", areas));
            }
            if (!styles.isEmpty()) {
                parts.add("风格=" + String.join("、", styles));
            }
            if (!objectives.isEmpty()) {
                parts.add("目标=" + String.join("、", objectives));
            }
            if (!duration.isBlank()) {
                parts.add("时长=" + duration);
            }
            if (!timePreference.isBlank()) {
                parts.add("时间=" + timePreference);
            }
            if (!mobilityPreference.isBlank()) {
                parts.add("强度=" + mobilityPreference);
            }
            if (!avoidTags.isEmpty()) {
                parts.add("避开=" + String.join("、", avoidTags));
            }
            if (acknowledgementOnly) {
                parts.add("ack_only=true");
            }
            if (requestLike) {
                parts.add("request_like=true");
            }
            return String.join("; ", parts);
        }
    }
}
