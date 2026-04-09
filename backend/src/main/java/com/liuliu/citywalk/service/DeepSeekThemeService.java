package com.liuliu.citywalk.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.config.DeepSeekProperties;
import com.liuliu.citywalk.model.dto.request.CombineThemeRequest;
import com.liuliu.citywalk.model.dto.request.GeneratePresetThemeRequest;
import com.liuliu.citywalk.model.dto.request.GenerateThemeRequest;
import com.liuliu.citywalk.model.dto.response.LocationContextResponse;
import com.liuliu.citywalk.model.dto.response.PoiResponse;
import com.liuliu.citywalk.model.dto.response.ThemeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DeepSeekThemeService {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekThemeService.class);
    private static final String PROVIDER = "deepseek";

    private final DeepSeekProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final MapSearchService mapSearchService;

    public DeepSeekThemeService(DeepSeekProperties properties, ObjectMapper objectMapper, MapSearchService mapSearchService) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.mapSearchService = mapSearchService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public ThemeResponse generateTheme(GenerateThemeRequest request) {
        ThemePayload fallback = new ThemePayload(
                "城市灵感漫步",
                "沿着今天的城市氛围慢下来，观察那些只在此刻出现的细节。",
                "探索",
                List.of("找到一个让你停下来的街景", "记录一种今天最明显的颜色或声音", "用一句话总结这段路的气质"),
                "#f59e0b"
        );

        String prompt = """
                你是一个 City Walk 主题策划助手。请根据下面信息，生成一个适合散步探索的中文主题。
                心情：%s
                天气：%s
                季节：%s
                偏好：%s
                地点：%s
                地点环境：%s
                漫步模式：%s

                请把“地点”理解为“以这个地址为中心，向周围 3 公里范围扩展”的探索区域，
                不要只盯着单一门牌或单一点位，要从周边街区、路口、店铺、公园、街景和生活氛围里设计主题与任务。

                请严格输出 JSON，不要输出额外解释。
                JSON 结构：
                {
                  "title": "不超过12个字",
                  "description": "1段 30-60 字的中文描述",
                  "category": "一个短分类词",
                  "missions": ["任务1", "任务2", "任务3"],
                  "vibeColor": "#RRGGBB"
                }
                """.formatted(
                request.mood(),
                request.weather(),
                request.season(),
                request.preference(),
                request.locationName(),
                request.locationContext(),
                request.walkMode()
        );
        return toThemeResponse(callThemePrompt(prompt, fallback), 1L);
    }

    public ThemeResponse generatePreset(GeneratePresetThemeRequest request) {
        ThemePayload fallback = new ThemePayload(
                request.category() + "主题",
                "从眼前的街区里挑一个角度慢慢走，看见这个地点最有意思的层次。",
                request.category(),
                List.of("找到一个最符合这个主题的细节", "记录一个容易被忽略的瞬间", "总结这个地点给你的第一印象"),
                "#3b82f6"
        );

        String prompt = """
                你是一个 City Walk 主题策划助手。请围绕“%s”这个方向，为地点“%s”生成一个中文漫步主题。
                地点环境：%s
                漫步模式：%s

                请把“地点”理解为“以这个地址为中心，向周围 3 公里范围扩展”的探索区域，
                让主题和任务尽量覆盖周边街区，而不是只围绕一个点。

                请严格输出 JSON，不要输出额外解释。
                JSON 结构：
                {
                  "title": "不超过12个字",
                  "description": "1段 30-60 字的中文描述",
                  "category": "%s",
                  "missions": ["任务1", "任务2", "任务3"],
                  "vibeColor": "#RRGGBB"
                }
                """.formatted(
                request.category(),
                request.locationName(),
                request.locationContext(),
                request.walkMode(),
                request.category()
        );
        return toThemeResponse(callThemePrompt(prompt, fallback), 2L);
    }

    public ThemeResponse combineTheme(CombineThemeRequest request) {
        String categoriesText = String.join("、", request.categories());
        ThemePayload fallback = new ThemePayload(
                "组合漫步",
                "把两个观察角度叠在一起，让这次散步同时有层次感和惊喜感。",
                "组合",
                List.of("找到一个同时符合多个主题的细节", "记录一次意外发现", "总结这段路线的整体气质"),
                "#8b5cf6"
        );

        String prompt = """
                你是一个 City Walk 主题策划助手。请把这些方向融合成一个新的中文漫步主题：%s。
                地点：%s
                地点环境：%s
                漫步模式：%s

                请把“地点”理解为“以这个地址为中心，向周围 3 公里范围扩展”的探索区域，
                任务设计要适合在周边多个街区或多个观察点之间步行探索。

                请严格输出 JSON，不要输出额外解释。
                JSON 结构：
                {
                  "title": "不超过12个字",
                  "description": "1段 30-60 字的中文描述",
                  "category": "组合",
                  "missions": ["任务1", "任务2", "任务3"],
                  "vibeColor": "#RRGGBB"
                }
                """.formatted(
                categoriesText,
                request.locationName(),
                request.locationContext(),
                request.walkMode()
        );
        return toThemeResponse(callThemePrompt(prompt, fallback), 3L);
    }

    public LocationContextResponse locationContext(Double lat, Double lng) {
        String fallback = "城市街区与生活化场景混合环境";
        String poiSummary = buildPoiSummary(lat, lng);
        String prompt = """
                你是一个地点环境描述助手。请根据经纬度推测此地点适合 City Walk 的环境氛围。
                纬度：%s
                经度：%s
                周边可逛点摘要：%s

                请不要只描述单一点位，而是把它理解为“以该位置为中心、周边 3 公里范围”的城市环境，
                概括这一片区域整体适合漫步的氛围。

                请只输出一行中文短句，15 到 30 个字，不要解释。
                """.formatted(lat, lng, poiSummary);
        return new LocationContextResponse(callTextPrompt(prompt, fallback));
    }

    public LocationContextResponse searchContext(String query) {
        String fallback = query + "附近以城市街区和生活场景为主";
        String prompt = """
                你是一个地点环境描述助手。请根据地点关键词生成一句适合 City Walk 的中文环境描述。
                地点关键词：%s

                请把这个地点理解为“以该地址为中心、周边 3 公里范围”的区域，
                描述这片区域整体的街区氛围与漫步感受，不要只写单个建筑。

                请只输出一行中文短句，15 到 30 个字，不要解释。
                """.formatted(query);
        return new LocationContextResponse(callTextPrompt(prompt, fallback));
    }

    private String buildPoiSummary(Double lat, Double lng) {
        List<String> poiTitles = mapSearchService.nearbyPois(lat, lng).stream()
                .map(PoiResponse::title)
                .filter(title -> title != null && !title.isBlank())
                .limit(6)
                .collect(Collectors.toList());
        if (poiTitles.isEmpty()) {
            return "暂无明显 POI，可按普通城市街区理解";
        }
        return String.join("、", poiTitles);
    }

    private ThemeResponse toThemeResponse(ThemePayload payload, Long id) {
        return new ThemeResponse(
                id,
                payload.title(),
                payload.description(),
                payload.category(),
                payload.missions(),
                payload.vibeColor(),
                PROVIDER,
                null
        );
    }

    private ThemePayload callThemePrompt(String prompt, ThemePayload fallback) {
        if (!isConfigured()) {
            log.info("DeepSeek skipped: api key not configured, using fallback theme");
            return fallback;
        }

        try {
            String raw = callDeepSeek(prompt, true);
            ThemePayload parsed = objectMapper.readValue(extractJsonObject(raw), ThemePayload.class);
            log.info("DeepSeek theme generated successfully with model {}", properties.getModel());
            return sanitizeThemePayload(parsed, fallback);
        } catch (Exception error) {
            log.warn("DeepSeek theme generation failed, using fallback: {}", error.getMessage());
            return fallback;
        }
    }

    private String callTextPrompt(String prompt, String fallback) {
        if (!isConfigured()) {
            log.info("DeepSeek skipped: api key not configured, using fallback text");
            return fallback;
        }

        try {
            String raw = callDeepSeek(prompt, false).trim();
            log.info("DeepSeek text generated successfully with model {}", properties.getModel());
            return raw.isBlank() ? fallback : raw;
        } catch (Exception error) {
            log.warn("DeepSeek text generation failed, using fallback: {}", error.getMessage());
            return fallback;
        }
    }

    private boolean isConfigured() {
        return properties.getApiKey() != null && !properties.getApiKey().isBlank();
    }

    private String callDeepSeek(String prompt, boolean expectJson) throws IOException, InterruptedException {
        Map<String, Object> requestBody = Map.of(
                "model", properties.getModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", "你是一个擅长中文城市漫步策划的助手。"),
                        Map.of("role", "user", "content", prompt)
                ),
                "temperature", 0.8,
                "response_format", Map.of("type", expectJson ? "json_object" : "text")
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getBaseUrl() + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("DeepSeek HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode contentNode = objectMapper.readTree(response.body())
                .path("choices").path(0).path("message").path("content");
        if (contentNode.isMissingNode() || contentNode.asText().isBlank()) {
            throw new IOException("DeepSeek returned empty content");
        }
        return contentNode.asText();
    }

    private ThemePayload sanitizeThemePayload(ThemePayload payload, ThemePayload fallback) {
        if (payload == null) {
            return fallback;
        }

        List<String> missions = payload.missions() == null
                ? fallback.missions()
                : payload.missions().stream().filter(item -> item != null && !item.isBlank()).limit(3).toList();

        if (missions.isEmpty()) {
            missions = fallback.missions();
        }

        return new ThemePayload(
                isBlank(payload.title()) ? fallback.title() : payload.title().trim(),
                isBlank(payload.description()) ? fallback.description() : payload.description().trim(),
                isBlank(payload.category()) ? fallback.category() : payload.category().trim(),
                missions,
                isBlank(payload.vibeColor()) ? fallback.vibeColor() : payload.vibeColor().trim()
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String extractJsonObject(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("No JSON object found in DeepSeek response");
        }
        return trimmed.substring(start, end + 1);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ThemePayload(
            String title,
            String description,
            String category,
            List<String> missions,
            String vibeColor
    ) {
    }
}
