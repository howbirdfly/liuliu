package com.liuliu.citywalk.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class EmbeddingAiConfiguration {

    @Bean
    public EmbeddingModel embeddingModel(EmbeddingAiProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMs = Math.max(1000, properties.getRequestTimeoutMs());
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(trimTrailingSlash(properties.getBaseUrl()))
                .embeddingsPath("/embeddings")
                .apiKey(normalize(properties.getApiKey()))
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();

        OpenAiEmbeddingOptions.Builder optionsBuilder = OpenAiEmbeddingOptions.builder()
                .model(defaultModel(properties.getModel()))
                .encodingFormat(defaultEncodingFormat(properties.getEncodingFormat()));
        if (properties.getDimensions() != null && properties.getDimensions() > 0) {
            optionsBuilder.dimensions(properties.getDimensions());
        }

        return new OpenAiEmbeddingModel(
                openAiApi,
                MetadataMode.NONE,
                optionsBuilder.build()
        );
    }

    private String trimTrailingSlash(String value) {
        return normalize(value).replaceAll("/+$", "");
    }

    private String defaultModel(String value) {
        String normalized = normalize(value);
        return normalized.isBlank() ? "text-embedding-v4" : normalized;
    }

    private String defaultEncodingFormat(String value) {
        String normalized = normalize(value);
        return normalized.isBlank() ? "float" : normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
