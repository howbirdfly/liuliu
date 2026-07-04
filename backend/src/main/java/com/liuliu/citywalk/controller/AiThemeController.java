package com.liuliu.citywalk.controller;

import com.liuliu.citywalk.common.ApiResponse;
import com.liuliu.citywalk.model.dto.request.CombineThemeRequest;
import com.liuliu.citywalk.model.dto.request.GeneratePresetThemeRequest;
import com.liuliu.citywalk.model.dto.request.GenerateThemeRequest;
import com.liuliu.citywalk.model.dto.request.GenerateWalkRecordCardRequest;
import com.liuliu.citywalk.model.dto.request.MissionVerifyRequest;
import com.liuliu.citywalk.model.dto.response.LocationContextResponse;
import com.liuliu.citywalk.model.dto.response.MissionVerifyResponse;
import com.liuliu.citywalk.model.dto.response.ThemeResponse;
import com.liuliu.citywalk.model.dto.response.ThemeStreamEventResponse;
import com.liuliu.citywalk.model.dto.response.WalkRecordCardTextResponse;
import com.liuliu.citywalk.service.MissionVerifyAiService;
import com.liuliu.citywalk.service.ThemeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/ai")
public class AiThemeController {

    private final ThemeService themeService;
    private final MissionVerifyAiService missionVerifyAiService;

    public AiThemeController(ThemeService themeService, MissionVerifyAiService missionVerifyAiService) {
        this.themeService = themeService;
        this.missionVerifyAiService = missionVerifyAiService;
    }

    @PostMapping("/themes/generate")
    public ApiResponse<ThemeResponse> generate(@Valid @RequestBody GenerateThemeRequest request) {
        return ApiResponse.success(themeService.generateTheme(request));
    }

    @GetMapping(value = "/themes/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> generateStream(
            @RequestParam String mood,
            @RequestParam String weather,
            @RequestParam String season,
            @RequestParam String preference,
            @RequestParam String locationName,
            @RequestParam String locationContext,
            @RequestParam String walkMode
    ) {
        GenerateThemeRequest request = new GenerateThemeRequest(
                mood,
                weather,
                season,
                preference,
                locationName,
                locationContext,
                walkMode
        );

        SseEmitter emitter = new SseEmitter(2L * 60L * 1000L);
        Thread.startVirtualThread(() -> {
            try {
                sendThemeEvent(emitter, new ThemeStreamEventResponse(
                        "start",
                        null,
                        null,
                        themeService.provider(),
                        themeService.model()
                ));

                ThemeResponse theme = themeService.streamGenerateTheme(
                        request,
                        delta -> sendThemeEvent(emitter, new ThemeStreamEventResponse(
                                "content_delta",
                                delta,
                                null,
                                themeService.provider(),
                                themeService.model()
                        ))
                );

                sendThemeEvent(emitter, new ThemeStreamEventResponse(
                        "complete",
                        null,
                        theme,
                        themeService.provider(),
                        themeService.model()
                ));
                emitter.complete();
            } catch (Exception error) {
                emitter.completeWithError(error);
            }
        });

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    @PostMapping("/themes/preset")
    public ApiResponse<ThemeResponse> generatePreset(@Valid @RequestBody GeneratePresetThemeRequest request) {
        return ApiResponse.success(themeService.generatePreset(request));
    }

    @PostMapping("/themes/combine")
    public ApiResponse<ThemeResponse> combine(@Valid @RequestBody CombineThemeRequest request) {
        return ApiResponse.success(themeService.combineTheme(request));
    }

    @PostMapping("/walk-record-card")
    public ApiResponse<WalkRecordCardTextResponse> generateWalkRecordCard(@Valid @RequestBody GenerateWalkRecordCardRequest request) {
        return ApiResponse.success(themeService.generateWalkRecordCardText(request));
    }

    @GetMapping("/location/context")
    public ApiResponse<LocationContextResponse> context(@RequestParam Double lat, @RequestParam Double lng) {
        return ApiResponse.success(themeService.locationContext(lat, lng));
    }

    @GetMapping("/location/search-context")
    public ApiResponse<LocationContextResponse> searchContext(@RequestParam String query) {
        return ApiResponse.success(themeService.searchContext(query));
    }

    @PostMapping("/missions/verify")
    public ApiResponse<MissionVerifyResponse> verifyMission(@RequestBody MissionVerifyRequest request) {
        return ApiResponse.success(missionVerifyAiService.verifyMission(request));
    }

    private void sendThemeEvent(SseEmitter emitter, ThemeStreamEventResponse event) {
        try {
            emitter.send(
                    SseEmitter.event()
                            .name(event.type())
                            .data(event, MediaType.APPLICATION_JSON)
            );
        } catch (Exception error) {
            throw new IllegalStateException("theme_stream_send_failed", error);
        }
    }
}
