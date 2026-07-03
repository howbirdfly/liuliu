package com.liuliu.citywalk.service.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class SpringAiEmbeddingService implements EmbeddingService {

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    public SpringAiEmbeddingService(ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.embeddingModelProvider = embeddingModelProvider;
    }

    @Override
    public String provider() {
        return "spring_ai_embedding_model";
    }

    @Override
    public boolean isConfigured() {
        return resolveEmbeddingModel() != null;
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

        EmbeddingModel embeddingModel = resolveEmbeddingModel();
        if (embeddingModel == null) {
            throw new IllegalStateException("embedding_not_configured");
        }

        List<String> normalizedTexts = texts.stream()
                .map(item -> item == null ? "" : item.trim())
                .filter(item -> !item.isBlank())
                .toList();
        if (normalizedTexts.isEmpty()) {
            return List.of();
        }

        return toFloatLists(embeddingModel.embed(normalizedTexts));
    }

    private EmbeddingModel resolveEmbeddingModel() {
        return embeddingModelProvider.getIfAvailable();
    }

    private List<List<Float>> toFloatLists(List<float[]> rawEmbeddings) {
        if (rawEmbeddings == null || rawEmbeddings.isEmpty()) {
            return List.of();
        }
        List<List<Float>> results = new ArrayList<>(rawEmbeddings.size());
        for (float[] rawEmbedding : rawEmbeddings) {
            results.add(toFloatList(rawEmbedding));
        }
        return results;
    }

    private List<Float> toFloatList(float[] rawEmbedding) {
        if (rawEmbedding == null || rawEmbedding.length == 0) {
            return List.of();
        }
        List<Float> result = new ArrayList<>(rawEmbedding.length);
        for (float value : rawEmbedding) {
            result.add(value);
        }
        return result;
    }
}
