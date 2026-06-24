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
        return "Fetch the details of one public walk record to enrich route planning context.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return jsonObjectSchema(
                Map.of(
                        "walkId", integerProperty("Public walk record id.")
                ),
                List.of("walkId")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        long walkId = longArg(arguments, "walkId", -1L);
        WalkResponse walk = walkId <= 0 ? null : walkService.getDetail(walkId);
        if (walk == null || !Boolean.TRUE.equals(walk.isPublic())) {
            return json(Map.of(
                    "success", true,
                    "walkId", walkId,
                    "found", false
            ));
        }
        return json(Map.of(
                "success", true,
                "walkId", walkId,
                "found", true,
                "result", walk
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
