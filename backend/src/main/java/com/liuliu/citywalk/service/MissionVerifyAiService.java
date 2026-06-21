package com.liuliu.citywalk.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.config.MissionVerifyAiProperties;
import com.liuliu.citywalk.model.dto.request.MissionVerifyRequest;
import com.liuliu.citywalk.model.dto.response.MissionVerifyResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MissionVerifyAiService {

    private static final Logger log = LoggerFactory.getLogger(MissionVerifyAiService.class);
    private static final String SYSTEM_PROMPT = "你是遛遛小程序的任务核验助手。请采用宽松、鼓励式标准判断用户是否完成了任务。只返回 JSON，字段为 passed、comment、confidence。即使不通过，也给简短温和评价。";
    private static final String MISSING_INPUT_COMMENT = "请至少上传一张图片，再让 AI 帮你判断是否完成了任务。";
    private static final String PASS_COMMENT = "这组记录和任务意图比较贴近，已经为你点亮任务。";
    private static final String FAIL_COMMENT = "这组记录已经很接近了，可以再补一张更贴近任务主题的图片。";
    private static final String FALLBACK_COMMENT = "AI 识别暂时较慢，已按宽松标准先为你记录这次打卡。";

    private final MissionVerifyAiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public MissionVerifyAiService(MissionVerifyAiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public MissionVerifyResponse verifyMission(MissionVerifyRequest request) {
        if (request == null || isBlank(request.mission())) {
            return new MissionVerifyResponse(false, MISSING_INPUT_COMMENT, "low", System.currentTimeMillis(), "missing_input");
        }

        List<String> imageUrls = collectImageUrls(request);
        if (imageUrls.isEmpty()) {
            return new MissionVerifyResponse(false, MISSING_INPUT_COMMENT, "low", System.currentTimeMillis(), "missing_input");
        }

        try {
            VerifyPayload payload = callVisionModel(request.mission(), request.noteText(), imageUrls);
            boolean passed = payload != null && payload.passed();
            return new MissionVerifyResponse(
                    passed,
                    firstNonBlank(payload == null ? null : payload.comment(), passed ? PASS_COMMENT : FAIL_COMMENT),
                    firstNonBlank(payload == null ? null : payload.confidence(), "medium"),
                    System.currentTimeMillis(),
                    null
            );
        } catch (Exception error) {
            log.warn("Mission verify AI failed, fallback to pass: {}", error.getMessage());
            return new MissionVerifyResponse(true, FALLBACK_COMMENT, "fallback", System.currentTimeMillis(), error.getMessage());
        }
    }

    private VerifyPayload callVisionModel(String mission, String noteText, List<String> imageUrls) throws IOException, InterruptedException {
        if (isBlank(properties.getApiKey())) {
            throw new IllegalStateException("missing_ai_api_key");
        }

        List<Object> content = new ArrayList<>();
        content.add(Map.of(
                "type", "text",
                "text", "任务内容：" + mission + "\n用户备注：" + firstNonBlank(noteText, "无") + "\n请宽松判断这些图片和备注是否基本符合任务意图。"
        ));
        for (String imageUrl : imageUrls) {
            content.add(Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", imageUrl)
            ));
        }

        Map<String, Object> requestBody = Map.of(
                "model", properties.getModel(),
                "temperature", 0.3,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", content)
                )
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(properties.getBaseUrl()) + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .timeout(Duration.ofMillis(Math.max(1000, safeTimeoutMs())))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("mission_verify_http_" + response.statusCode() + ": " + response.body());
        }

        JsonNode contentNode = objectMapper.readTree(response.body())
                .path("choices").path(0).path("message").path("content");
        String rawContent = contentNode.isMissingNode() ? "" : contentNode.asText("");
        if (rawContent.isBlank()) {
            throw new IOException("mission_verify_empty_content");
        }
        return objectMapper.readValue(stripCodeFence(rawContent), VerifyPayload.class);
    }

    private List<String> collectImageUrls(MissionVerifyRequest request) {
        Set<String> urls = new LinkedHashSet<>();
        appendUrls(urls, request.fileUrls(), false);
        appendUrls(urls, request.fileIDs(), true);
        return new ArrayList<>(urls);
    }

    private void appendUrls(Set<String> target, List<String> values, boolean httpOnly) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (isBlank(value)) {
                continue;
            }
            String trimmed = value.trim();
            if (httpOnly && !isHttpUrl(trimmed)) {
                continue;
            }
            target.add(trimmed);
        }
    }

    private boolean isHttpUrl(String value) {
        return value.startsWith("http://") || value.startsWith("https://");
    }

    private int safeTimeoutMs() {
        Integer timeoutMs = properties.getRequestTimeoutMs();
        return timeoutMs == null ? 2200 : timeoutMs;
    }

    private String trimTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String firstNonBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private String stripCodeFence(String value) {
        return value
                .replaceFirst("^```json\\s*", "")
                .replaceFirst("^```\\s*", "")
                .replaceFirst("\\s*```$", "")
                .trim();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VerifyPayload(
            boolean passed,
            String comment,
            String confidence
    ) {
    }
}
