package com.liuliu.citywalk.service.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.service.MapSearchService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
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
        return "Search nearby places of interest around a latitude and longitude.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return jsonObjectSchema(
                Map.of(
                        "lat", numberProperty("Latitude."),
                        "lng", numberProperty("Longitude.")
                ),
                List.of("lat", "lng")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        double lat = doubleArg(arguments, "lat", Double.NaN);
        double lng = doubleArg(arguments, "lng", Double.NaN);
        MapSearchService.AgentMapSearchResult<?> searchResult = mapSearchService.nearbyPoisForAgent(
                Double.isNaN(lat) ? null : lat,
                Double.isNaN(lng) ? null : lng
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lat", lat);
        payload.put("lng", lng);
        payload.put("success", searchResult.success());
        payload.put("results", searchResult.results());
        if (searchResult.error() != null) {
            payload.put("error", searchResult.error());
            payload.put("fallbackSuggestion", "If nearby POI search fails, fall back to area-level suggestions and avoid claiming exact places were confirmed.");
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
