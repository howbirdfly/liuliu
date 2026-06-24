package com.liuliu.citywalk.service.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.service.rag.KnowledgeHit;
import com.liuliu.citywalk.service.rag.KnowledgeSearchService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(prefix = "liuliu.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SearchKnowledgeBaseAgentTool extends AbstractJsonAgentTool {

    private final KnowledgeSearchService knowledgeSearchService;

    public SearchKnowledgeBaseAgentTool(ObjectMapper objectMapper, KnowledgeSearchService knowledgeSearchService) {
        super(objectMapper);
        this.knowledgeSearchService = knowledgeSearchService;
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

        List<KnowledgeHit> hits = knowledgeSearchService.search(query, topK, filters);
        return json(Map.of(
                "success", true,
                "query", query,
                "topK", topK,
                "sourceType", sourceType,
                "results", hits
        ));
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
