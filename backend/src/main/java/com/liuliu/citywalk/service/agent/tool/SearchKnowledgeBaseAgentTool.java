package com.liuliu.citywalk.service.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.service.rag.SpringAiKnowledgeDocumentService;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(prefix = "liuliu.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SearchKnowledgeBaseAgentTool extends AbstractJsonAgentTool {

    private final SpringAiKnowledgeDocumentService springAiKnowledgeDocumentService;

    public SearchKnowledgeBaseAgentTool(
            ObjectMapper objectMapper,
            SpringAiKnowledgeDocumentService springAiKnowledgeDocumentService
    ) {
        super(objectMapper);
        this.springAiKnowledgeDocumentService = springAiKnowledgeDocumentService;
    }

    @Override
    public String name() {
        return "search_knowledge_base";
    }

    @Override
    public String description() {
        return "Search vectorized City Walk knowledge, such as public guides, route summaries, and historical walking content.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return jsonObjectSchema(
                Map.of(
                        "query", stringProperty("Semantic search query, such as seaside sunset, campus walk, or old-street photography."),
                        "topK", integerProperty("Maximum number of results to return. Defaults to 5."),
                        "sourceType", stringProperty("Optional source type filter, such as community_walk.")
                ),
                List.of("query")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String query = stringArg(arguments, "query");
        int topK = Math.min(Math.max(1, intArg(arguments, "topK", 5)), 10);
        String sourceType = stringArg(arguments, "sourceType");

        Map<String, Object> filters = new LinkedHashMap<>();
        if (!sourceType.isBlank()) {
            filters.put("source_type", sourceType);
        }

        List<Document> documents = springAiKnowledgeDocumentService.search(query, topK, filters);
        return json(Map.of(
                "success", true,
                "query", query,
                "topK", topK,
                "sourceType", sourceType,
                "results", documents.stream().map(this::toResult).toList()
        ));
    }

    private Map<String, Object> toResult(Document document) {
        Map<String, Object> metadata = document.getMetadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(document.getMetadata());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chunkId", readString(metadata, "chunk_id", document.getId()));
        result.put("sourceId", readString(metadata, "source_id", ""));
        result.put("sourceType", readString(metadata, "source_type", ""));
        result.put("title", readString(metadata, "title", ""));
        result.put("content", document.getText() == null ? "" : document.getText());
        result.put("score", readDouble(document.getScore(), metadata.get("score")));
        result.put("metadata", metadata);
        return result;
    }

    private String readString(Map<String, Object> metadata, String key, String fallback) {
        Object value = metadata.get(key);
        if (value == null) {
            return fallback;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() ? fallback : normalized;
    }

    private double readDouble(Double score, Object fallbackValue) {
        if (score != null) {
            return score;
        }
        if (fallbackValue instanceof Number number) {
            return number.doubleValue();
        }
        if (fallbackValue instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return 0.0d;
            }
        }
        return 0.0d;
    }

    @Override
    public boolean supportsIdempotentReplay() {
        return true;
    }

    @Override
    public boolean supportsSharedResultCache() {
        return true;
    }
}
