package com.liuliu.citywalk.service.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.model.dto.response.WalkResponse;
import com.liuliu.citywalk.service.WalkService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class GetWalkDetailAgentTool extends AbstractJsonAgentTool {

    private final WalkService walkService;

    public GetWalkDetailAgentTool(ObjectMapper objectMapper, WalkService walkService) {
        super(objectMapper);
        this.walkService = walkService;
    }

    @Override
    public String name() {
        return "get_walk_detail";
    }

    @Override
    public String description() {
        return "查看一条公开 Walk 记录的详细信息，用于补充路线灵感。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "walkId", Map.of(
                                "type", "integer",
                                "description", "公开 Walk 记录的 id"
                        )
                ),
                "required", List.of("walkId"),
                "additionalProperties", false
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        long walkId = longArg(arguments, "walkId", -1L);
        WalkResponse walk = walkId <= 0 ? null : walkService.getDetail(walkId);
        if (walk == null || !Boolean.TRUE.equals(walk.isPublic())) {
            return json(Map.of(
                    "walkId", walkId,
                    "found", false
            ));
        }
        return json(Map.of(
                "walkId", walkId,
                "found", true,
                "result", walk
        ));
    }
}
