package com.liuliu.citywalk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.config.AmapProperties;
import com.liuliu.citywalk.model.dto.response.LocationSearchResponse;
import com.liuliu.citywalk.model.dto.response.PoiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class MapSearchService {

    private static final Logger log = LoggerFactory.getLogger(MapSearchService.class);
    private static final int SEARCH_RADIUS_METERS = 3000;
    private static final int MAX_SEARCH_RESULTS = 5;
    private static final int MAX_POI_RESULTS = 12;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AmapProperties amapProperties;

    public MapSearchService(ObjectMapper objectMapper, AmapProperties amapProperties) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = objectMapper;
        this.amapProperties = amapProperties;
    }

    public List<LocationSearchResponse> search(String query) {
        String keyword = query == null ? "" : query.trim();
        if (keyword.isBlank()) {
            return List.of();
        }

        if (!isConfigured()) {
            return fallbackLocations(keyword);
        }

        try {
            String url = amapProperties.getBaseUrl()
                    + "/v3/place/text?keywords=" + encode(keyword)
                    + "&offset=" + MAX_SEARCH_RESULTS
                    + "&page=1&extensions=base&key=" + encode(amapProperties.getWebKey());
            JsonNode root = sendGet(url);
            JsonNode pois = root.path("pois");
            if (!pois.isArray() || pois.isEmpty()) {
                return fallbackLocations(keyword);
            }

            List<LocationSearchResponse> results = new ArrayList<>();
            for (JsonNode poi : pois) {
                double[] location = parseLocation(poi.path("location").asText());
                if (location == null) {
                    continue;
                }
                results.add(new LocationSearchResponse(
                        buildLocationDisplayName(poi),
                        location[1],
                        location[0]
                ));
            }
            return results.isEmpty() ? fallbackLocations(keyword) : results;
        } catch (Exception error) {
            return fallbackLocations(keyword);
        }
    }

    public AgentMapSearchResult<LocationSearchResponse> searchForAgent(String query) {
        String keyword = query == null ? "" : query.trim();
        if (keyword.isBlank()) {
            return new AgentMapSearchResult<>(true, null, null, List.of());
        }

        if (!isConfigured()) {
            return new AgentMapSearchResult<>(
                    false,
                    "map_search_unavailable",
                    "map_service_not_configured",
                    List.of()
            );
        }

        try {
            String url = amapProperties.getBaseUrl()
                    + "/v3/place/text?keywords=" + encode(keyword)
                    + "&offset=" + MAX_SEARCH_RESULTS
                    + "&page=1&extensions=base&key=" + encode(amapProperties.getWebKey());
            JsonNode root = sendGet(url);
            JsonNode pois = root.path("pois");
            if (!pois.isArray() || pois.isEmpty()) {
                return new AgentMapSearchResult<>(true, null, null, List.of());
            }

            List<LocationSearchResponse> results = new ArrayList<>();
            for (JsonNode poi : pois) {
                double[] location = parseLocation(poi.path("location").asText());
                if (location == null) {
                    continue;
                }
                results.add(new LocationSearchResponse(
                        buildLocationDisplayName(poi),
                        location[1],
                        location[0]
                ));
            }
            return new AgentMapSearchResult<>(true, null, null, results);
        } catch (Exception error) {
            log.warn("Agent map search failed for query={}: {}", keyword, error.getMessage());
            return new AgentMapSearchResult<>(
                    false,
                    "map_search_failed",
                    error.getMessage() == null || error.getMessage().isBlank() ? "unknown_error" : error.getMessage(),
                    List.of()
            );
        }
    }

    public List<PoiResponse> nearbyPois(Double lat, Double lng) {
        if (lat == null || lng == null) {
            return List.of();
        }

        if (!isConfigured()) {
            return fallbackPois(lat, lng);
        }

        try {
            String location = lng + "," + lat;
            String url = amapProperties.getBaseUrl()
                    + "/v3/place/around?location=" + encode(location)
                    + "&radius=" + SEARCH_RADIUS_METERS
                    + "&sortrule=distance&offset=" + MAX_POI_RESULTS
                    + "&page=1&extensions=base&key=" + encode(amapProperties.getWebKey());
            JsonNode root = sendGet(url);
            JsonNode pois = root.path("pois");
            if (!pois.isArray() || pois.isEmpty()) {
                return fallbackPois(lat, lng);
            }

            List<PoiResponse> results = new ArrayList<>();
            for (JsonNode poi : pois) {
                double[] poiLocation = parseLocation(poi.path("location").asText());
                if (poiLocation == null) {
                    continue;
                }
                String title = poi.path("name").asText();
                if (title == null || title.isBlank()) {
                    continue;
                }
                results.add(new PoiResponse(
                        title,
                        buildAmapLink(title, poiLocation[0], poiLocation[1]),
                        poiLocation[1],
                        poiLocation[0]
                ));
            }
            return results.isEmpty() ? fallbackPois(lat, lng) : results;
        } catch (Exception error) {
            return fallbackPois(lat, lng);
        }
    }

    public AgentMapSearchResult<PoiResponse> nearbyPoisForAgent(Double lat, Double lng) {
        if (lat == null || lng == null) {
            return new AgentMapSearchResult<>(true, null, null, List.of());
        }

        if (!isConfigured()) {
            return new AgentMapSearchResult<>(
                    false,
                    "map_search_unavailable",
                    "map_service_not_configured",
                    List.of()
            );
        }

        try {
            String location = lng + "," + lat;
            String url = amapProperties.getBaseUrl()
                    + "/v3/place/around?location=" + encode(location)
                    + "&radius=" + SEARCH_RADIUS_METERS
                    + "&sortrule=distance&offset=" + MAX_POI_RESULTS
                    + "&page=1&extensions=base&key=" + encode(amapProperties.getWebKey());
            JsonNode root = sendGet(url);
            JsonNode pois = root.path("pois");
            if (!pois.isArray() || pois.isEmpty()) {
                return new AgentMapSearchResult<>(true, null, null, List.of());
            }

            List<PoiResponse> results = new ArrayList<>();
            for (JsonNode poi : pois) {
                double[] poiLocation = parseLocation(poi.path("location").asText());
                if (poiLocation == null) {
                    continue;
                }
                String title = poi.path("name").asText();
                if (title == null || title.isBlank()) {
                    continue;
                }
                results.add(new PoiResponse(
                        title,
                        buildAmapLink(title, poiLocation[0], poiLocation[1]),
                        poiLocation[1],
                        poiLocation[0]
                ));
            }
            return new AgentMapSearchResult<>(true, null, null, results);
        } catch (Exception error) {
            log.warn("Agent nearby poi search failed for lat={}, lng={}: {}", lat, lng, error.getMessage());
            return new AgentMapSearchResult<>(
                    false,
                    "map_search_failed",
                    error.getMessage() == null || error.getMessage().isBlank() ? "unknown_error" : error.getMessage(),
                    List.of()
            );
        }
    }

    private JsonNode sendGet(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("Amap HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        String status = root.path("status").asText();
        if (!"1".equals(status)) {
            throw new IOException("Amap API error: " + root.path("info").asText("unknown"));
        }
        return root;
    }

    private boolean isConfigured() {
        return amapProperties.getWebKey() != null && !amapProperties.getWebKey().isBlank();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private double[] parseLocation(String value) {
        if (value == null || value.isBlank() || !value.contains(",")) {
            return null;
        }
        String[] parts = value.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private String buildLocationDisplayName(JsonNode poi) {
        String name = poi.path("name").asText("");
        String address = poi.path("address").asText("");
        String district = poi.path("adname").asText("");
        String city = poi.path("cityname").asText("");
        String suffix = String.join(" ", List.of(city, district, address).stream()
                .filter(item -> item != null && !item.isBlank())
                .toList());
        return suffix.isBlank() ? name : name + " - " + suffix;
    }

    private List<LocationSearchResponse> fallbackLocations(String keyword) {
        return List.of(
                new LocationSearchResponse(keyword + " 附近", 31.2304, 121.4737),
                new LocationSearchResponse(keyword + " 街区", 31.2282, 121.4781),
                new LocationSearchResponse(keyword + " 漫步点", 31.2249, 121.4695)
        );
    }

    private List<PoiResponse> fallbackPois(Double lat, Double lng) {
        return List.of(
                new PoiResponse("附近咖啡馆", buildAmapLink("附近咖啡馆", lng, lat), lat, lng),
                new PoiResponse("城市书店", buildAmapLink("城市书店", lng + 0.0011, lat + 0.0012), lat + 0.0012, lng + 0.0011),
                new PoiResponse("街角展览空间", buildAmapLink("街角展览空间", lng + 0.0008, lat - 0.0009), lat - 0.0009, lng + 0.0008)
        );
    }

    private String buildAmapLink(String name, double lng, double lat) {
        return "https://uri.amap.com/marker?position=" + lng + "," + lat + "&name=" + encode(name);
    }

    public record AgentMapSearchResult<T>(
            boolean success,
            String error,
            String message,
            List<T> results
    ) {
    }
}
