package com.liuliu.citywalk.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.config.GeminiProperties;
import com.liuliu.citywalk.model.dto.request.CombineThemeRequest;
import com.liuliu.citywalk.model.dto.request.GeneratePresetThemeRequest;
import com.liuliu.citywalk.model.dto.request.GenerateThemeRequest;
import com.liuliu.citywalk.model.dto.response.LocationContextResponse;
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

@Service
public class GeminiThemeService {

    private static final String PROVIDER = "gemini";
    private static final Logger log = LoggerFactory.getLogger(GeminiThemeService.class);

    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GeminiThemeService(GeminiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
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
        String prompt = """
                你是一个地点环境描述助手。请根据经纬度推测此地点适合 City Walk 的环境氛围。
                纬度：%s
                经度：%s

                请只输出一行中文短句，15 到 30 个字，不要解释。
                """.formatted(lat, lng);
        return new LocationContextResponse(callTextPrompt(prompt, fallback));
    }

    public LocationContextResponse searchContext(String query) {
        String fallback = query + "附近以城市街区和生活场景为主";
        String prompt = """
                你是一个地点环境描述助手。请根据地点关键词生成一句适合 City Walk 的中文环境描述。
                地点关键词：%s

                请只输出一行中文短句，15 到 30 个字，不要解释。
                """.formatted(query);
        return new LocationContextResponse(callTextPrompt(prompt, fallback));
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
            log.info("Gemini skipped: api key not configured, using fallback theme");
            return fallback;
        }

        try {
            String raw = callGemini(prompt, "application/json");
            String jsonText = extractJsonObject(raw);
            ThemePayload parsed = objectMapper.readValue(jsonText, ThemePayload.class);
            log.info("Gemini theme generated successfully with model {}", properties.getModel());
            return sanitizeThemePayload(parsed, fallback);
        } catch (Exception error) {
            log.warn("Gemini theme generation failed, using fallback: {}", error.getMessage());
            return fallback;
        }
    }

    private String callTextPrompt(String prompt, String fallback) {
        if (!isConfigured()) {
            log.info("Gemini skipped: api key not configured, using fallback text");
            return fallback;
        }

        try {
            String raw = callGemini(prompt, "text/plain");
            String normalized = raw == null ? "" : raw.trim();
            log.info("Gemini text generated successfully with model {}", properties.getModel());
            return normalized.isBlank() ? fallback : normalized;
        } catch (Exception error) {
            log.warn("Gemini text generation failed, using fallback: {}", error.getMessage());
            return fallback;
        }
    }

    private boolean isConfigured() {
        return properties.getApiKey() != null && !properties.getApiKey().isBlank();
    }

    private String callGemini(String prompt, String responseMimeType) throws IOException, InterruptedException {
        GeminiRequest requestBody = new GeminiRequest(
                List.of(new GeminiContent(List.of(new GeminiPart(prompt)))),
                new GeminiGenerationConfig(0.8, responseMimeType)
        );

        String endpoint = properties.getBaseUrl()
                + "/v1beta/models/"
                + properties.getModel()
                + ":generateContent?key="
                + properties.getApiKey();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("Gemini HTTP " + response.statusCode() + ": " + response.body());
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode textNode = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
        if (textNode.isMissingNode() || textNode.asText().isBlank()) {
            throw new IOException("Gemini returned empty content");
        }
        return textNode.asText();
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
            throw new IllegalArgumentException("No JSON object found in Gemini response");
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

    private record GeminiRequest(
            List<GeminiContent> contents,
            GeminiGenerationConfig generationConfig
    ) {
    }

    private record GeminiContent(
            List<GeminiPart> parts
    ) {
    }

    private record GeminiPart(
            String text
    ) {
    }

    private record GeminiGenerationConfig(
            Double temperature,
            String responseMimeType
    ) {
    }
}
