package com.liuliu.citywalk.service.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.service.MapSearchService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class NearbyPoisAgentTool extends AbstractJsonAgentTool {

    private final MapSearchService mapSearchService;

    public NearbyPoisAgentTool(ObjectMapper objectMapper, MapSearchService mapSearchService) {
        super(objectMapper);
        this.mapSearchService = mapSearchService;
    }

    @Override
    public String name() {
        return "nearby_pois";
    }

    @Override
    public String description() {
        return "根据经纬度查询周边适合漫步探索的兴趣点。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "lat", Map.of("type", "number", "description", "纬度"),
                        "lng", Map.of("type", "number", "description", "经度")
                ),
                "required", List.of("lat", "lng"),
                "additionalProperties", false
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        double lat = doubleArg(arguments, "lat", Double.NaN);
        double lng = doubleArg(arguments, "lng", Double.NaN);
        return json(Map.of(
                "lat", lat,
                "lng", lng,
                "results", mapSearchService.nearbyPois(Double.isNaN(lat) ? null : lat, Double.isNaN(lng) ? null : lng)
        ));
    }
}
