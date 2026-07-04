package com.liuliu.citywalk.service;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

@Service
public class ThemeAiClient {

    private static final String PROVIDER = "deepseek";
    private static final String SYSTEM_PROMPT =
            "你是一名擅长中文表达的 City Walk 策划助手。严格按照要求返回内容，不要输出多余解释。";

    private final SpringAiPromptExecutor promptExecutor;
    private final ChatModel chatModel;
    private final String configuredModel;
    private final String configuredApiKey;

    public ThemeAiClient(
            SpringAiPromptExecutor promptExecutor,
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            @Value("${spring.ai.deepseek.chat.model:deepseek-chat}") String configuredModel,
            @Value("${spring.ai.deepseek.api-key:}") String configuredApiKey
    ) {
        this.promptExecutor = promptExecutor;
        this.chatModel = chatModel;
        this.configuredModel = configuredModel == null || configuredModel.isBlank()
                ? "deepseek-chat"
                : configuredModel.trim();
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

    public <T> T callJson(String prompt, Class<T> responseType) {
        return promptExecutor.callStructured(chatModel, buildPrompt(prompt, promptExecutor.structuredFormat(responseType)), responseType);
    }

    public <T> T callJsonStreaming(String prompt, Consumer<String> listener, Class<T> responseType) {
        return promptExecutor.callStructuredStreaming(
                chatModel,
                buildPrompt(prompt, promptExecutor.structuredFormat(responseType)),
                listener,
                responseType
        );
    }

    public String callText(String prompt) {
        return promptExecutor.callText(chatModel, buildPrompt(prompt, null)).trim();
    }

    private Prompt buildPrompt(String prompt, String outputFormat) {
        String normalizedPrompt = outputFormat == null || outputFormat.isBlank()
                ? prompt
                : prompt + "\n\n" + outputFormat;
        return new Prompt(
                List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(normalizedPrompt)),
                ChatOptions.builder()
                        .model(model())
                        .temperature(0.8d)
                        .build()
        );
    }
}
