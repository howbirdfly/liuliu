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
        return "检索社区公开攻略、Walk 记录和相关路线灵感。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "keyword", Map.of(
                                "type", "string",
                                "description", "检索关键词，例如复古街区、拍照、夜景、亲子。"
                        ),
                        "page", Map.of("type", "integer", "description", "页码，默认 1"),
                        "pageSize", Map.of("type", "integer", "description", "返回数量，默认 5")
                ),
                "required", List.of("keyword"),
                "additionalProperties", false
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
}
