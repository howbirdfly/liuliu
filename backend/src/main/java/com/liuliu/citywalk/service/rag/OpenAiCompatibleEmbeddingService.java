package com.liuliu.citywalk.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.config.EmbeddingProperties;
import com.liuliu.citywalk.service.agent.AgentExecutionCancelledException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OpenAiCompatibleEmbeddingService implements EmbeddingService {

    private final EmbeddingProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiCompatibleEmbeddingService(EmbeddingProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String provider() {
        return "openai_compatible_embeddings";
    }

    @Override
    public boolean isConfigured() {
        return properties.isEnabled()
                && properties.getApiKey() != null
                && !properties.getApiKey().isBlank()
                && properties.getBaseUrl() != null
                && !properties.getBaseUrl().isBlank()
                && properties.getModel() != null
                && !properties.getModel().isBlank();
    }

    @Override
    public List<Float> embed(String text) {
        List<List<Float>> embeddings = embedAll(List.of(text));
        return embeddings.isEmpty() ? List.of() : embeddings.getFirst();
    }

    @Override
    public List<List<Float>> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (!isConfigured()) {
            throw new IllegalStateException("embedding_not_configured");
        }

        List<String> normalizedTexts = texts.stream()
                .map(item -> item == null ? "" : item.trim())
                .filter(item -> !item.isBlank())
                .toList();
        if (normalizedTexts.isEmpty()) {
            return List.of();
        }

        try {
            List<List<Float>> embeddings = new ArrayList<>();
            int batchSize = Math.max(1, Math.min(properties.getBatchSize(), 10));
            for (int start = 0; start < normalizedTexts.size(); start += batchSize) {
                int end = Math.min(normalizedTexts.size(), start + batchSize);
                embeddings.addAll(requestEmbeddings(normalizedTexts.subList(start, end)));
            }
            return embeddings;
        } catch (AgentExecutionCancelledException error) {
            throw error;
        } catch (Exception error) {
            if (error instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new AgentExecutionCancelledException("agent_execution_cancelled", error);
            }
            throw new IllegalStateException("embedding_request_failed: " + error.getMessage(), error);
        }
    }

    private List<List<Float>> requestEmbeddings(List<String> texts) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("input", texts);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(normalizeUrl(properties.getBaseUrl(), properties.getPath())))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .timeout(Duration.ofMillis(Math.max(2000L, properties.getRequestTimeoutMs())))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("Embedding HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode dataNode = objectMapper.readTree(response.body()).path("data");
        if (!dataNode.isArray()) {
            throw new IOException("embedding_response_invalid");
        }

        List<List<Float>> embeddings = new ArrayList<>();
        for (JsonNode item : dataNode) {
            JsonNode embeddingNode = item.path("embedding");
            if (!embeddingNode.isArray()) {
                continue;
            }
            List<Float> vector = new ArrayList<>(embeddingNode.size());
            for (JsonNode value : embeddingNode) {
                vector.add((float) value.asDouble());
            }
            embeddings.add(vector);
        }
        return embeddings;
    }

    private String normalizeUrl(String baseUrl, String path) {
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedPath = path == null || path.isBlank() ? "/embeddings" : path.trim();
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }
        return normalizedBase + normalizedPath;
    }
}
