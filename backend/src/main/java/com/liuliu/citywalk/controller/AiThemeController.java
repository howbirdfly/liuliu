package com.liuliu.citywalk.controller;

import com.liuliu.citywalk.common.ApiResponse;
import com.liuliu.citywalk.model.dto.request.CombineThemeRequest;
import com.liuliu.citywalk.model.dto.request.GeneratePresetThemeRequest;
import com.liuliu.citywalk.model.dto.request.GenerateThemeRequest;
import com.liuliu.citywalk.model.dto.request.GenerateWalkRecordCardRequest;
import com.liuliu.citywalk.model.dto.request.MiniappMissionVerifyRequest;
import com.liuliu.citywalk.model.dto.response.LocationContextResponse;
import com.liuliu.citywalk.model.dto.response.MiniappMissionVerifyResponse;
import com.liuliu.citywalk.model.dto.response.ThemeResponse;
import com.liuliu.citywalk.model.dto.response.WalkRecordCardTextResponse;
import com.liuliu.citywalk.service.DeepSeekThemeService;
import com.liuliu.citywalk.service.MissionVerifyAiService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai")
public class AiThemeController {

    private final DeepSeekThemeService deepSeekThemeService;
    private final MissionVerifyAiService missionVerifyAiService;

    public AiThemeController(DeepSeekThemeService deepSeekThemeService, MissionVerifyAiService missionVerifyAiService) {
        this.deepSeekThemeService = deepSeekThemeService;
        this.missionVerifyAiService = missionVerifyAiService;
    }

    @PostMapping("/themes/generate")
    public ApiResponse<ThemeResponse> generate(@Valid @RequestBody GenerateThemeRequest request) {
        return ApiResponse.success(deepSeekThemeService.generateTheme(request));
    }

    @PostMapping("/themes/preset")
    public ApiResponse<ThemeResponse> generatePreset(@Valid @RequestBody GeneratePresetThemeRequest request) {
        return ApiResponse.success(deepSeekThemeService.generatePreset(request));
    }

    @PostMapping("/themes/combine")
    public ApiResponse<ThemeResponse> combine(@Valid @RequestBody CombineThemeRequest request) {
        return ApiResponse.success(deepSeekThemeService.combineTheme(request));
    }

    @PostMapping("/walk-record-card")
    public ApiResponse<WalkRecordCardTextResponse> generateWalkRecordCard(@Valid @RequestBody GenerateWalkRecordCardRequest request) {
        return ApiResponse.success(deepSeekThemeService.generateWalkRecordCardText(request));
    }

    @GetMapping("/location/context")
    public ApiResponse<LocationContextResponse> context(@RequestParam Double lat, @RequestParam Double lng) {
        return ApiResponse.success(deepSeekThemeService.locationContext(lat, lng));
    }

    @GetMapping("/location/search-context")
    public ApiResponse<LocationContextResponse> searchContext(@RequestParam String query) {
        return ApiResponse.success(deepSeekThemeService.searchContext(query));
    }

    @PostMapping("/missions/verify")
    public ApiResponse<MiniappMissionVerifyResponse> verifyMission(@RequestBody MiniappMissionVerifyRequest request) {
        return ApiResponse.success(missionVerifyAiService.verifyMission(request));
    }
}
