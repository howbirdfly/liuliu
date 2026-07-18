package com.liuliu.citywalk.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatProperties;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekConnectionProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class DeepSeekChatModelConfiguration {

    @Bean("liuliuDeepSeekChatModel")
    public DeepSeekChatModel liuliuDeepSeekChatModel(
            DeepSeekConnectionProperties connectionProperties,
            DeepSeekChatProperties chatProperties,
            DeepSeekAiProperties deepSeekAiProperties,
            ObjectProvider<RestClient.Builder> restClientBuilderProvider,
            ObjectProvider<WebClient.Builder> webClientBuilderProvider,
            ToolCallingManager toolCallingManager,
            RetryTemplate retryTemplate,
            ResponseErrorHandler responseErrorHandler,
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            ObjectProvider<ChatModelObservationConvention> observationConventionProvider,
            ObjectProvider<ToolExecutionEligibilityPredicate> toolExecutionEligibilityPredicateProvider
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int connectTimeoutMs = Math.max(1000, deepSeekAiProperties.getConnectTimeoutMs());
        int requestTimeoutMs = Math.max(1000, deepSeekAiProperties.getRequestTimeoutMs());
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(requestTimeoutMs);

        RestClient.Builder restClientBuilder = restClientBuilderProvider.getIfAvailable(RestClient::builder)
                .requestFactory(requestFactory);
        WebClient.Builder webClientBuilder = webClientBuilderProvider.getIfAvailable(WebClient::builder);

        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .baseUrl(trimTrailingSlash(connectionProperties.getBaseUrl()))
                .apiKey(normalize(connectionProperties.getApiKey()))
                .completionsPath(defaultText(chatProperties.getCompletionsPath(), "/chat/completions"))
                .betaPrefixPath(defaultText(chatProperties.getBetaPrefixPath(), "/beta"))
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(webClientBuilder)
                .responseErrorHandler(responseErrorHandler)
                .build();

        DeepSeekChatOptions defaultOptions = chatProperties.getOptions() == null
                ? DeepSeekChatOptions.builder().model("deepseek-chat").build()
                : chatProperties.getOptions();

        DeepSeekChatModel chatModel = DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(defaultOptions)
                .toolCallingManager(toolCallingManager)
                .toolExecutionEligibilityPredicate(toolExecutionEligibilityPredicateProvider.getIfUnique())
                .retryTemplate(retryTemplate)
                .observationRegistry(observationRegistryProvider.getIfUnique(() -> ObservationRegistry.NOOP))
                .build();

        observationConventionProvider.ifAvailable(chatModel::setObservationConvention);
        return chatModel;
    }

    private String trimTrailingSlash(String value) {
        return normalize(value).replaceAll("/+$", "");
    }

    private String defaultText(String value, String fallback) {
        String normalized = normalize(value);
        return normalized.isBlank() ? fallback : normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
