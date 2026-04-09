package com.liuliu.citywalk.controller;

import com.liuliu.citywalk.common.ApiResponse;
import com.liuliu.citywalk.model.dto.request.CreateThemeRequest;
import com.liuliu.citywalk.model.dto.response.ThemeResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/themes")
public class ThemeController {

    @PostMapping
    public ApiResponse<ThemeResponse> create(@Valid @RequestBody CreateThemeRequest request) {
        ThemeResponse response = new ThemeResponse(
                100L,
                request.title(),
                request.description(),
                request.category(),
                request.missions(),
                "#14b8a6",
                "user",
                request.coverImageUrl()
        );
        return ApiResponse.success(response);
    }

    @GetMapping
    public ApiResponse<List<ThemeResponse>> list(@RequestParam(defaultValue = "1") Integer page,
                                                 @RequestParam(defaultValue = "20") Integer pageSize,
                                                 @RequestParam(defaultValue = "approved") String status) {
        List<ThemeResponse> response = List.of(
                new ThemeResponse(
                        101L,
                        "夜色霓虹",
                        "观察夜色如何改变街道气质",
                        "视觉",
                        List.of("寻找一处霓虹倒影", "观察一段灯光变化", "记录一条适合慢走的街"),
                        "#ec4899",
                        "community",
                        null
                )
        );
        return ApiResponse.success(response);
    }

    @GetMapping("/{themeId}")
    public ApiResponse<ThemeResponse> detail(@PathVariable Long themeId) {
        ThemeResponse response = new ThemeResponse(
                themeId,
                "夜色霓虹",
                "观察夜色如何改变街道气质",
                "视觉",
                List.of("寻找一处霓虹倒影", "观察一段灯光变化", "记录一条适合慢走的街"),
                "#ec4899",
                "community",
                null
        );
        return ApiResponse.success(response);
    }
}
