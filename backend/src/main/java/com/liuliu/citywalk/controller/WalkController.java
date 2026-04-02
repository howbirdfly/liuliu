package com.liuliu.citywalk.controller;

import com.liuliu.citywalk.common.ApiResponse;
import com.liuliu.citywalk.model.dto.request.CreateWalkRequest;
import com.liuliu.citywalk.model.dto.response.WalkResponse;
import com.liuliu.citywalk.service.WalkService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.List;

@RestController
@RequestMapping("/api/v1/walks")
public class WalkController {

    private static final Long WEB_DEBUG_USER_ID = 1001L;

    private final WalkService walkService;

    public WalkController(WalkService walkService) {
        this.walkService = walkService;
    }

    @PostMapping
    public ApiResponse<WalkResponse> create(@Valid @RequestBody CreateWalkRequest request) {
        WalkResponse record = walkService.create(WEB_DEBUG_USER_ID, request);
        return ApiResponse.success(record);
    }

    @GetMapping("/me")
    public ApiResponse<List<WalkResponse>> myWalks(@RequestParam(defaultValue = "1") Integer page,
                                                   @RequestParam(defaultValue = "20") Integer pageSize) {
        return ApiResponse.success(walkService.listMyWalks(WEB_DEBUG_USER_ID, pageSize));
    }

    @GetMapping("/public")
    public ApiResponse<List<WalkResponse>> publicWalks(@RequestParam(defaultValue = "1") Integer page,
                                                       @RequestParam(defaultValue = "20") Integer pageSize) {
        return ApiResponse.success(walkService.listPublicWalks(pageSize));
    }

    @GetMapping("/{walkId}")
    public ApiResponse<WalkResponse> detail(@PathVariable Long walkId) {
        return ApiResponse.success(walkService.getDetail(walkId));
    }
}
