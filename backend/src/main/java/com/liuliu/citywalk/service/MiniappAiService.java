package com.liuliu.citywalk.service;

import com.liuliu.citywalk.model.dto.request.CombineThemeRequest;
import com.liuliu.citywalk.model.dto.request.GeneratePresetThemeRequest;
import com.liuliu.citywalk.model.dto.request.GenerateThemeRequest;
import com.liuliu.citywalk.model.dto.request.MiniappGenerateRandomThemeRequest;
import com.liuliu.citywalk.model.dto.request.MiniappLocationContextRequest;
import com.liuliu.citywalk.model.dto.request.MiniappMissionVerifyRequest;
import com.liuliu.citywalk.model.dto.response.LocationContextResponse;
import com.liuliu.citywalk.model.dto.response.MiniappLocationContextResponse;
import com.liuliu.citywalk.model.dto.response.MiniappMissionVerifyResponse;
import com.liuliu.citywalk.model.dto.response.MiniappThemeResultResponse;
import com.liuliu.citywalk.model.dto.response.PoiResponse;
import com.liuliu.citywalk.model.dto.response.ThemeResponse;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MiniappAiService {

    private static final List<String> RANDOM_CATEGORIES = List.of("形状漫步", "色彩漫步", "声音漫步", "纹理漫步", "市井漫步", "自然漫步");

    private final DeepSeekThemeService deepSeekThemeService;
    private final MapSearchService mapSearchService;
    private final MissionVerifyAiService missionVerifyAiService;

    public MiniappAiService(
            DeepSeekThemeService deepSeekThemeService,
            MapSearchService mapSearchService,
            MissionVerifyAiService missionVerifyAiService
    ) {
        this.deepSeekThemeService = deepSeekThemeService;
        this.mapSearchService = mapSearchService;
        this.missionVerifyAiService = missionVerifyAiService;
    }

    public MiniappThemeResultResponse generateTheme(GenerateThemeRequest request) {
        ThemeResponse theme = deepSeekThemeService.generateTheme(request);
        return new MiniappThemeResultResponse(theme, "rag+ai");
    }

    public MiniappThemeResultResponse generateRandomTheme(MiniappGenerateRandomThemeRequest request) {
        String category = request.category();
        if (category == null || category.isBlank()) {
            category = RANDOM_CATEGORIES.get((int) (System.currentTimeMillis() % RANDOM_CATEGORIES.size()));
        }
        ThemeResponse theme = deepSeekThemeService.generatePreset(new GeneratePresetThemeRequest(
                category,
                safeText(request.locationName(), "当前位置"),
                safeText(request.locationContext(), "城市街道"),
                safeText(request.walkMode(), "pure")
        ));
        return new MiniappThemeResultResponse(theme, "random+ai");
    }

    public MiniappThemeResultResponse generateCombinedTheme(CombineThemeRequest request) {
        if (request.categories() == null || request.categories().size() < 2) {
            ThemeResponse fallback = new ThemeResponse(
                    0L,
                    "组合探索",
                    "至少选择两个方向后，再生成组合主题。",
                    "组合",
                    List.of("选择两个方向后再试一次"),
                    "#7c6a94",
                    "backend",
                    null
            );
            return new MiniappThemeResultResponse(fallback, "combined-fallback");
        }
        ThemeResponse theme = deepSeekThemeService.combineTheme(request);
        return new MiniappThemeResultResponse(theme, "combined+ai");
    }

    public MiniappLocationContextResponse getLocationContext(MiniappLocationContextRequest request) {
        String placeName = safeText(request.placeName(), resolvePlaceName(request.latitude(), request.longitude()));
        LocationContextResponse context = deepSeekThemeService.locationContext(request.latitude(), request.longitude());
        return new MiniappLocationContextResponse(
                safeText(context.locationContext(), placeName),
                placeName
        );
    }

    public MiniappMissionVerifyResponse verifyMission(MiniappMissionVerifyRequest request) {
        return missionVerifyAiService.verifyMission(request);
    }

    private String resolvePlaceName(Double latitude, Double longitude) {
        List<PoiResponse> pois = mapSearchService.nearbyPois(latitude, longitude);
        if (pois.isEmpty()) {
            return "当前位置";
        }
        String title = pois.get(0).title();
        return title == null || title.isBlank() ? "当前位置" : title;
    }

    private String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
