package com.liuliu.citywalk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.model.dto.response.AgentStepResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AgentAnswerFormatterService {

    private static final List<String> FIXED_HEADINGS = List.of(
            "## 推荐区域",
            "## 路线顺序",
            "## 依据来源",
            "## 不确定项",
            "## 实用提醒"
    );

    private final ObjectMapper objectMapper;

    public AgentAnswerFormatterService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String formatFinalAnswer(
            String rawAnswer,
            AgentIntentAnalysisService.AgentIntent intent,
            List<AgentStepResponse> steps
    ) {
        String normalized = normalize(rawAnswer);
        if (isClarificationStyleAnswer(normalized)) {
            return normalized;
        }
        if (containsAllSections(normalized)) {
            return normalized;
        }

        String recommendedArea = buildRecommendedArea(normalized, intent);
        List<String> routeOrder = buildRouteOrder(normalized, intent);
        List<String> sources = buildSources(steps);
        List<String> uncertainties = buildUncertainties(normalized, intent, steps);
        List<String> reminders = buildReminders(intent);

        List<String> sections = new ArrayList<>();
        sections.add("## 推荐区域\n" + recommendedArea);
        sections.add("## 路线顺序\n" + toNumberedList(routeOrder));
        sections.add("## 依据来源\n" + toBulletList(sources));
        sections.add("## 不确定项\n" + toBulletList(uncertainties));
        sections.add("## 实用提醒\n" + toBulletList(reminders));
        return String.join("\n\n", sections).trim();
    }

    private boolean isClarificationStyleAnswer(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = normalize(value);
        String lower = normalized.toLowerCase(Locale.ROOT);
        boolean asksQuestion = normalized.contains("?")
                || normalized.contains("？")
                || lower.contains("想先问你")
                || lower.contains("可以告诉我")
                || lower.contains("请告诉我")
                || lower.contains("请确认")
                || lower.contains("先问你")
                || lower.contains("还想问你");
        return asksQuestion && !containsAllSections(normalized);
    }

    private boolean containsAllSections(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (String heading : FIXED_HEADINGS) {
            if (!value.contains(heading)) {
                return false;
            }
        }
        return true;
    }

    private String buildRecommendedArea(String rawAnswer, AgentIntentAnalysisService.AgentIntent intent) {
        if (intent != null && !intent.areas().isEmpty()) {
            return "建议优先从 " + String.join("、", trimList(intent.areas(), 2)) + " 一带展开，这样更贴近你这轮的地点诉求。";
        }
        if (intent != null && !intent.cities().isEmpty()) {
            String city = intent.cities().getFirst();
            if (!intent.styles().isEmpty() || !intent.objectives().isEmpty()) {
                List<String> preferences = new ArrayList<>();
                preferences.addAll(trimList(intent.styles(), 2));
                preferences.addAll(trimList(intent.objectives(), 2));
                return "建议先在 " + city + " 里围绕 " + String.join("、", trimList(preferences, 3)) + " 这个方向选区，再开始走。";
            }
            return "建议先从 " + city + " 里步行氛围更稳定、可停留点更密集的片区开始。";
        }
        String firstSentence = firstSentence(rawAnswer);
        if (!firstSentence.isBlank()) {
            return firstSentence;
        }
        return "建议先从你当前最方便到达、并且适合步行停留的区域开始，再按现场状态微调。";
    }

    private List<String> buildRouteOrder(String rawAnswer, AgentIntentAnalysisService.AgentIntent intent) {
        List<String> extracted = extractOrderedRouteLines(rawAnswer);
        if (!extracted.isEmpty()) {
            return extracted;
        }

        List<String> fallbacks = new ArrayList<>();
        if (intent != null && !intent.areas().isEmpty()) {
            for (String area : trimList(intent.areas(), 3)) {
                fallbacks.add("先围绕 " + area + " 附近步行展开，沿路筛选适合停留的点位。");
            }
        }
        if (fallbacks.isEmpty() && intent != null && !intent.cities().isEmpty()) {
            fallbacks.add("先到 " + intent.cities().getFirst() + " 的一个核心步行片区落脚。");
        }
        if (fallbacks.isEmpty()) {
            fallbacks.add("先从最容易抵达的出发点开始。");
        }
        fallbacks.add("中段根据现场看到的街景、店铺和停留感受决定是否继续延展。");
        fallbacks.add("最后在适合收尾的点结束，比如咖啡馆、江边、街角广场或地铁站附近。");
        return trimList(fallbacks, 4);
    }

    private List<String> buildSources(List<AgentStepResponse> steps) {
        Set<String> sources = new LinkedHashSet<>();
        if (steps != null) {
            for (AgentStepResponse step : steps) {
                if (step == null || step.name() == null) {
                    continue;
                }
                switch (step.name()) {
                    case "search_knowledge_base" -> sources.add("知识库检索结果");
                    case "search_poi" -> sources.add("地图 POI 搜索结果");
                    case "nearby_pois" -> sources.add("附近 POI 检索结果");
                    case "search_community_guides" -> sources.add("社区公开路线和攻略");
                    case "get_walk_detail" -> sources.add("公开 Walk 详情页");
                    default -> {
                    }
                }
            }
        }
        if (sources.isEmpty()) {
            sources.add("本轮用户需求和已有上下文");
        }
        return new ArrayList<>(sources);
    }

    private List<String> buildUncertainties(
            String rawAnswer,
            AgentIntentAnalysisService.AgentIntent intent,
            List<AgentStepResponse> steps
    ) {
        Set<String> items = new LinkedHashSet<>();
        if (rawAnswer != null) {
            String lower = rawAnswer.toLowerCase(Locale.ROOT);
            if (lower.contains("推测") || lower.contains("大致") || lower.contains("可能") || lower.contains("未确认")) {
                items.add("答案里有一部分是根据上下文做的保守推断，不是全部都由工具实时确认。");
            }
        }
        if (steps != null) {
            for (AgentStepResponse step : steps) {
                if (step == null || step.output() == null || step.output().isBlank()) {
                    continue;
                }
                if (containsToolFailure(step.output())) {
                    items.add("有些工具结果不完整或未命中，所以具体点位和顺序仍需要你出发时再看一眼地图。");
                    break;
                }
            }
        }
        if (intent != null && !intent.avoidTags().isEmpty()) {
            items.add("你提到的避开项我已经尽量考虑，但实时人流、排队和封路情况仍要以现场为准。");
        }
        if (items.isEmpty()) {
            items.add("具体店铺营业状态、实时人流和临时交通管制，仍建议你出发前再用地图确认一次。");
        }
        return new ArrayList<>(items);
    }

    private List<String> buildReminders(AgentIntentAnalysisService.AgentIntent intent) {
        List<String> reminders = new ArrayList<>();
        if (intent != null && !intent.duration().isBlank()) {
            reminders.add("这次你预期的时长是 " + intent.duration() + "，走的时候可以每段都留一点弹性。");
        } else {
            reminders.add("建议边走边看，不要把所有停留点都卡得太满。");
        }
        if (intent != null && !intent.mobilityPreference().isBlank()) {
            reminders.add("你这轮偏 " + intent.mobilityPreference() + " 节奏，路线里尽量少做大幅折返。");
        } else {
            reminders.add("如果现场某段体验一般，优先换区，不要硬走完整条线。");
        }
        if (intent != null && !intent.timePreference().isBlank()) {
            reminders.add("你更偏 " + intent.timePreference() + " 出发，注意把最值得看的段落放在那个时间窗口。");
        }
        return trimList(reminders, 3);
    }

    private List<String> extractOrderedRouteLines(String rawAnswer) {
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return List.of();
        }
        List<String> results = new ArrayList<>();
        String[] lines = rawAnswer.split("\\R");
        for (String line : lines) {
            String trimmed = normalize(line);
            if (trimmed.isBlank()) {
                continue;
            }
            if (trimmed.startsWith("##")) {
                continue;
            }
            if (trimmed.matches("^\\d+\\..*")) {
                results.add(trimmed.replaceFirst("^\\d+\\.\\s*", ""));
                continue;
            }
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                String value = trimmed.substring(2).trim();
                if (value.contains("先") || value.contains("再") || value.contains("然后") || value.contains("最后")) {
                    results.add(value);
                }
            }
        }
        if (!results.isEmpty()) {
            return trimList(results, 5);
        }

        List<String> sentences = splitSentences(rawAnswer);
        for (String sentence : sentences) {
            if (sentence.contains("先") || sentence.contains("再") || sentence.contains("然后") || sentence.contains("最后")) {
                results.add(sentence);
            }
        }
        return trimList(results, 4);
    }

    private boolean containsToolFailure(String output) {
        try {
            Object value = objectMapper.readValue(output, Object.class);
            if (!(value instanceof Map<?, ?> map)) {
                return false;
            }
            Object success = map.get("success");
            if (success instanceof Boolean bool && !bool) {
                return true;
            }
            Object found = map.get("found");
            if (found instanceof Boolean bool && !bool) {
                return true;
            }
            Object results = map.get("results");
            if (results instanceof List<?> list && list.isEmpty()) {
                return true;
            }
            return map.containsKey("error");
        } catch (Exception ignored) {
            return false;
        }
    }

    private String toNumberedList(List<String> items) {
        List<String> normalized = trimList(items, 5);
        if (normalized.isEmpty()) {
            normalized = List.of("按现场步行感受灵活调整，优先保留最顺路、最有停留价值的两到三个点。");
        }
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < normalized.size(); index++) {
            lines.add((index + 1) + ". " + normalized.get(index));
        }
        return String.join("\n", lines);
    }

    private String toBulletList(List<String> items) {
        List<String> normalized = trimList(items, 5);
        if (normalized.isEmpty()) {
            normalized = List.of("暂无");
        }
        List<String> lines = new ArrayList<>();
        for (String item : normalized) {
            lines.add("- " + item);
        }
        return String.join("\n", lines);
    }

    private List<String> splitSentences(String text) {
        List<String> results = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return results;
        }
        for (String part : text.split("[。！？\\n]")) {
            String normalized = normalize(part);
            if (!normalized.isBlank()) {
                results.add(normalized);
            }
        }
        return results;
    }

    private String firstSentence(String text) {
        List<String> sentences = splitSentences(text);
        return sentences.isEmpty() ? "" : sentences.getFirst();
    }

    private <T> List<T> trimList(List<T> items, int maxSize) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<T> result = new ArrayList<>();
        for (T item : items) {
            if (item == null) {
                continue;
            }
            if (item instanceof String str && normalize(str).isBlank()) {
                continue;
            }
            result.add(item);
            if (result.size() >= maxSize) {
                break;
            }
        }
        return result;
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\u3000', ' ').trim();
    }
}
