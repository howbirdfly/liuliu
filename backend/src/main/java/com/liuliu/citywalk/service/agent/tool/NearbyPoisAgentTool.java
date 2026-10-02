package com.liuliu.citywalk.service.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.model.dto.response.LocationSearchResponse;
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
        return "Search nearby places of interest around a location. "
                + "Provide either lat+lng, or a place name via query (the server geocodes it first).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return jsonObjectSchema(
                Map.of(
                        "query", stringProperty("Place name or address to center the search on, "
                                + "such as Wukang Road. Use this when latitude/longitude are unknown."),
                        "lat", numberProperty("Latitude. Prefer reusing coordinates returned by search_poi."),
                        "lng", numberProperty("Longitude. Prefer reusing coordinates returned by search_poi.")
                ),
                List.of()
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        double lat = doubleArg(arguments, "lat", Double.NaN);
        double lng = doubleArg(arguments, "lng", Double.NaN);
        String query = stringArg(arguments, "query");

        Double resolvedLat = Double.isNaN(lat) ? null : lat;
        Double resolvedLng = Double.isNaN(lng) ? null : lng;
        String centerName = null;
        String geocodeError = null;

        // 模型在规划阶段通常拿不到坐标,只会给地名。这里先地理编码再查周边,
        // 避免"必填 lat/lng 校验失败 -> 白白浪费一轮工具调用"。
        if ((resolvedLat == null || resolvedLng == null) && !query.isBlank()) {
            MapSearchService.AgentMapSearchResult<LocationSearchResponse> geocodeResult =
                    mapSearchService.geocodeForAgent(query);
            if (geocodeResult.success() && geocodeResult.results() != null && !geocodeResult.results().isEmpty()) {
                LocationSearchResponse center = geocodeResult.results().get(0);
                resolvedLat = center.lat();
                resolvedLng = center.lng();
                centerName = center.name();
            } else {
                geocodeError = geocodeResult.error() == null ? "geocode_no_result" : geocodeResult.error();
            }
        }

        if (resolvedLat == null || resolvedLng == null) {
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("success", false);
            failure.put("error", geocodeError == null ? "tool_arguments_invalid" : geocodeError);
            failure.put("message", geocodeError == null
                    ? "nearby_pois requires either lat/lng or a place name in query"
                    : "failed to resolve coordinates for query=" + query);
            failure.put("canContinue", true);
            failure.put("fallbackSuggestion", "Do not retry with the same arguments; "
                    + "call search_poi with the place name instead, or give area-level suggestions.");
            failure.put("results", List.of());
            return json(failure);
        }

        MapSearchService.AgentMapSearchResult<?> searchResult =
                mapSearchService.nearbyPoisForAgent(resolvedLat, resolvedLng);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lat", resolvedLat);
        payload.put("lng", resolvedLng);
        if (!query.isBlank()) {
            payload.put("query", query);
        }
        if (centerName != null && !centerName.isBlank()) {
            payload.put("center", centerName);
        }
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
