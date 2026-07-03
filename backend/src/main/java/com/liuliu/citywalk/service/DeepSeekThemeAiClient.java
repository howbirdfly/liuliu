package com.liuliu.citywalk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class DeepSeekThemeAiClient {

    private static final String PROVIDER = "deepseek";
    private static final String SYSTEM_PROMPT =
            "你是一名擅长中文表达的 City Walk 策划助手。严格按照要求返回内容，不要输出多余解释。";

    private final ObjectMapper objectMapper;
    private final ChatModel chatModel;
    private final String configuredModel;
    private final String configuredApiKey;

    public DeepSeekThemeAiClient(
            ObjectMapper objectMapper,
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            @Value("${spring.ai.deepseek.chat.model:deepseek-chat}") String configuredModel,
            @Value("${spring.ai.deepseek.api-key:}") String configuredApiKey
    ) {
        this.objectMapper = objectMapper;
        this.chatModel = chatModel;
        this.configuredModel = configuredModel == null || configuredModel.isBlank() ? "deepseek-chat" : configuredModel.trim();
        this.configuredApiKey = configuredApiKey == null ? "" : configuredApiKey.trim();
    }

    public String provider() {
        return PROVIDER;
    }

    public String model() {
        return configuredModel;
    }

    public boolean isConfigured() {
        return !configuredApiKey.isBlank() && chatModel != null;
    }

    public <T> T callJson(String prompt, Class<T> responseType) throws Exception {
        return objectMapper.readValue(extractJsonObject(call(prompt, true)), responseType);
    }

    public <T> T callJsonStreaming(String prompt, ThemeStreamListener listener, Class<T> responseType) throws Exception {
        return objectMapper.readValue(extractJsonObject(callStreaming(prompt, listener)), responseType);
    }

    public String callText(String prompt) {
        return call(prompt, false).trim();
    }

    private String callStreaming(String prompt, ThemeStreamListener listener) {
        AtomicReference<ChatResponse> aggregatedResponse = new AtomicReference<>();
        new MessageAggregator()
                .aggregate(getStreamingChatModel().stream(buildPrompt(prompt, true)), aggregatedResponse::set)
                .doOnNext(chunk -> emitStreamingDelta(chunk, listener))
                .blockLast();

        String content = extractText(aggregatedResponse.get());
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("DeepSeek returned empty streamed content");
        }
        return content;
    }

    private String call(String prompt, boolean expectJson) {
        String content = extractText(chatModel.call(buildPrompt(prompt, expectJson)));
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("DeepSeek returned empty content");
        }
        return content;
    }

    private StreamingChatModel getStreamingChatModel() {
        if (chatModel instanceof StreamingChatModel compatibleStreamingChatModel) {
            return compatibleStreamingChatModel;
        }
        throw new IllegalStateException("StreamingChatModel is not available");
    }

    private Prompt buildPrompt(String prompt, boolean expectJson) {
        String normalizedPrompt = expectJson
                ? prompt + "\n\n只返回一个 JSON 对象，不要使用 markdown 代码块。"
                : prompt;
        return new Prompt(
                List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(normalizedPrompt)),
                ChatOptions.builder()
                        .model(model())
                        .temperature(0.8d)
                        .build()
        );
    }

    private void emitStreamingDelta(ChatResponse chunk, ThemeStreamListener listener) {
        if (listener == null) {
            return;
        }
        String delta = extractText(chunk);
        if (delta != null && !delta.isEmpty()) {
            listener.onContentDelta(delta);
        }
    }

    private String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        return response.getResult().getOutput().getText();
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
}
