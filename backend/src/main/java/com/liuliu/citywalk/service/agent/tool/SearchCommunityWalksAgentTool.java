package com.liuliu.citywalk.service.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.service.CommunityService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class SearchCommunityWalksAgentTool extends AbstractJsonAgentTool {

    private final CommunityService communityService;

    public SearchCommunityWalksAgentTool(ObjectMapper objectMapper, CommunityService communityService) {
        super(objectMapper);
        this.communityService = communityService;
    }

    @Override
    public String name() {
        return "search_community_guides";
    }

    @Override
    public String description() {
        return "Search public community guides, walk records, and related route inspiration.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return jsonObjectSchema(
                Map.of(
                        "keyword", stringProperty("Search keyword, such as old street, photography, night view, or family friendly."),
                        "page", integerProperty("Page number. Defaults to 1."),
                        "pageSize", integerProperty("Maximum number of results to return. Defaults to 5.")
                ),
                List.of("keyword")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String keyword = stringArg(arguments, "keyword");
        int page = Math.max(1, intArg(arguments, "page", 1));
        int pageSize = Math.min(Math.max(1, intArg(arguments, "pageSize", 5)), 10);
        return json(Map.of(
                "success", true,
                "keyword", keyword,
                "page", page,
                "pageSize", pageSize,
                "results", communityService.searchWalks(keyword, null, page, pageSize)
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
