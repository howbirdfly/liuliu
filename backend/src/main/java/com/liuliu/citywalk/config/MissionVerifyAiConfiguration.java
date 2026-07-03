package com.liuliu.citywalk.config;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class MissionVerifyAiConfiguration {

    @Bean("missionVerifyChatModel")
    public OpenAiChatModel missionVerifyChatModel(MissionVerifyAiProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMs = Math.max(1000, properties.getRequestTimeoutMs());
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(trimTrailingSlash(properties.getBaseUrl()))
                .completionsPath("/chat/completions")
                .apiKey(normalize(properties.getApiKey()))
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(defaultModel(properties.getModel()))
                        .temperature(0.3)
                        .responseFormat(ResponseFormat.builder()
                                .type(ResponseFormat.Type.JSON_OBJECT)
                                .build())
                        .build())
                .build();
    }

    private String trimTrailingSlash(String value) {
        return normalize(value).replaceAll("/+$", "");
    }

    private String defaultModel(String value) {
        String normalized = normalize(value);
        return normalized.isBlank() ? "qwen-vl-plus" : normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
