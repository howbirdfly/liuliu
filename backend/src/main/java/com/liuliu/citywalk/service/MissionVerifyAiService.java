package com.liuliu.citywalk.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.liuliu.citywalk.config.MissionVerifyAiProperties;
import com.liuliu.citywalk.model.dto.request.MissionVerifyRequest;
import com.liuliu.citywalk.model.dto.response.MissionVerifyResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class MissionVerifyAiService {

    private static final Logger log = LoggerFactory.getLogger(MissionVerifyAiService.class);
    private static final String SYSTEM_PROMPT = """
            你是一个负责判断 City Walk 小任务是否完成的图片核验助手。
            你会结合任务文字、用户备注和多张图片，判断用户是否已经基本完成任务。
            你的输出必须严格遵循指定格式，不要补充额外解释。
            """;
    private static final String MISSING_INPUT_COMMENT = "请至少提供一张图片后再让我帮你判断这次任务是否完成。";
    private static final String PASS_COMMENT = "这组图片和任务描述基本一致，可以判定这次任务已经完成。";
    private static final String FAIL_COMMENT = "目前图片和任务目标还不够贴合，建议再补一张更能体现任务内容的照片。";
    private static final String FALLBACK_COMMENT = "AI 识图暂时不可用，这次先按完成处理；如果你愿意，也可以稍后再补一次更清晰的图片。";

    private final SpringAiPromptExecutor promptExecutor;
    private final MissionVerifyAiProperties properties;
    private final ChatModel missionVerifyChatModel;

    public MissionVerifyAiService(
            SpringAiPromptExecutor promptExecutor,
            MissionVerifyAiProperties properties,
            @Qualifier("missionVerifyChatModel") ChatModel missionVerifyChatModel
    ) {
        this.promptExecutor = promptExecutor;
        this.properties = properties;
        this.missionVerifyChatModel = missionVerifyChatModel;
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

    private VerifyPayload callVisionModel(String mission, String noteText, List<String> imageUrls) {
        if (isBlank(properties.getApiKey())) {
            throw new IllegalStateException("missing_ai_api_key");
        }

        UserMessage userMessage = UserMessage.builder()
                .text(buildUserPrompt(mission, noteText, promptExecutor.structuredFormat(VerifyPayload.class)))
                .media(toImageMedia(imageUrls))
                .build();

        return promptExecutor.callStructured(missionVerifyChatModel, new Prompt(
                List.of(new SystemMessage(SYSTEM_PROMPT), userMessage)
        ), VerifyPayload.class);
    }

    private String buildUserPrompt(String mission, String noteText, String outputFormat) {
        return """
                任务内容：%s
                用户备注：%s

                请结合这些图片判断任务是否已经完成。
                判断时以“是否能从图片中看出与任务目标基本一致的内容”为准，不要过度苛刻。

                %s
                """.formatted(mission, firstNonBlank(noteText, "无"), outputFormat);
    }

    private List<Media> toImageMedia(List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return List.of();
        }

        List<Media> media = new ArrayList<>(imageUrls.size());
        for (String imageUrl : imageUrls) {
            if (isBlank(imageUrl)) {
                continue;
            }
            media.add(new Media(detectImageMimeType(imageUrl), URI.create(imageUrl.trim())));
        }
        return media;
    }

    private MimeType detectImageMimeType(String imageUrl) {
        String normalized = imageUrl == null ? "" : imageUrl.trim().toLowerCase();
        int queryIndex = normalized.indexOf('?');
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }
        if (normalized.endsWith(".png")) {
            return MimeTypeUtils.IMAGE_PNG;
        }
        if (normalized.endsWith(".gif")) {
            return MimeTypeUtils.parseMimeType("image/gif");
        }
        if (normalized.endsWith(".webp")) {
            return MimeTypeUtils.parseMimeType("image/webp");
        }
        return MimeTypeUtils.IMAGE_JPEG;
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String firstNonBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VerifyPayload(
            boolean passed,
            String comment,
            String confidence
    ) {
    }
}
