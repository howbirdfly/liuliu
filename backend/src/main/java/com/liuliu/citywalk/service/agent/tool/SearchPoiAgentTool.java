package com.liuliu.citywalk.service.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.service.MapSearchService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SearchPoiAgentTool extends AbstractJsonAgentTool {

    private final MapSearchService mapSearchService;

    public SearchPoiAgentTool(ObjectMapper objectMapper, MapSearchService mapSearchService) {
        super(objectMapper);
        this.mapSearchService = mapSearchService;
    }

    @Override
    public String name() {
        return "search_poi";
    }

    @Override
    public String description() {
        return "Search candidate places that may fit a City Walk query.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return jsonObjectSchema(
                Map.of(
                        "query", stringProperty("Place keyword to search, such as Wukang Road, Qianmen, or a cafe street.")
                ),
                List.of("query")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String query = stringArg(arguments, "query");
        MapSearchService.AgentMapSearchResult<?> searchResult = mapSearchService.searchForAgent(query);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("success", searchResult.success());
        payload.put("results", searchResult.results());
        if (searchResult.error() != null) {
            payload.put("error", searchResult.error());
            payload.put("fallbackSuggestion", "When map search is unavailable, continue from community guides and the user's stated area preference, and keep the route suggestion conservative.");
        }
        if (searchResult.message() != null) {
            payload.put("message", searchResult.message());
        }
        return json(payload);
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
