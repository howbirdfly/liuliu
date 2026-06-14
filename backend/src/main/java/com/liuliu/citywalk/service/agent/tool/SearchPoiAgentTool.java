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
        return "按关键词搜索适合 City Walk 的地点候选。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of(
                                "type", "string",
                                "description", "用户想搜索的地点关键词，例如武康路、前门、咖啡街区。"
                        )
                ),
                "required", List.of("query"),
                "additionalProperties", false
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
            payload.put("fallbackSuggestion", "地图结果不可用时，可以继续参考社区公开攻略和用户已给出的区域偏好，提供保守路线建议。");
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
