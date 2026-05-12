package com.liuliu.citywalk.controller;

import com.liuliu.citywalk.common.ApiResponse;
import com.liuliu.citywalk.model.dto.response.CommunityWalkResponse;
import com.liuliu.citywalk.service.CommunityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/community")
public class CommunityController {

    private final CommunityService communityService;

    public CommunityController(CommunityService communityService) {
        this.communityService = communityService;
    }

    @GetMapping("/search")
    public ApiResponse<List<CommunityWalkResponse>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize
    ) {
        return ApiResponse.success(communityService.searchWalks(keyword, page, pageSize));
    }

    @GetMapping("/feed/latest")
    public ApiResponse<List<CommunityWalkResponse>> latest(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize
    ) {
        return ApiResponse.success(communityService.latestFeed(page, pageSize));
    }

    @GetMapping("/feed/hot")
    public ApiResponse<List<CommunityWalkResponse>> hot(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize
    ) {
        return ApiResponse.success(communityService.hotFeed(page, pageSize));
    }

    @GetMapping("/feed/recommend")
    public ApiResponse<List<CommunityWalkResponse>> recommend(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize
    ) {
        return ApiResponse.success(communityService.recommendFeed(page, pageSize));
    }
}
