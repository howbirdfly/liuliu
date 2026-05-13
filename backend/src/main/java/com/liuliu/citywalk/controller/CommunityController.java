package com.liuliu.citywalk.controller;

import com.liuliu.citywalk.common.ApiResponse;
import com.liuliu.citywalk.model.dto.request.CommunityCommentCreateRequest;
import com.liuliu.citywalk.model.dto.response.CommunityCommentResponse;
import com.liuliu.citywalk.model.dto.response.CommunityEngagementResponse;
import com.liuliu.citywalk.model.dto.response.CommunityWalkResponse;
import com.liuliu.citywalk.service.AuthTokenService;
import com.liuliu.citywalk.service.CommunityService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/community")
public class CommunityController {

    private final CommunityService communityService;
    private final AuthTokenService authTokenService;

    public CommunityController(CommunityService communityService, AuthTokenService authTokenService) {
        this.communityService = communityService;
        this.authTokenService = authTokenService;
    }

    @GetMapping("/search")
    public ApiResponse<List<CommunityWalkResponse>> search(
            HttpServletRequest request,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize
    ) {
        return ApiResponse.success(communityService.searchWalks(keyword, resolveOptionalUserId(request), page, pageSize));
    }

    @GetMapping("/walks/{walkId}")
    public ApiResponse<CommunityWalkResponse> detail(HttpServletRequest request, @PathVariable Long walkId) {
        try {
            return ApiResponse.success(communityService.getWalkDetail(walkId, resolveOptionalUserId(request)));
        } catch (IllegalStateException error) {
            return ApiResponse.fail(resolveBusinessErrorCode(error), error.getMessage());
        }
    }

    @GetMapping("/walks/{walkId}/comments")
    public ApiResponse<List<CommunityCommentResponse>> comments(@PathVariable Long walkId) {
        try {
            return ApiResponse.success(communityService.listComments(walkId));
        } catch (IllegalStateException error) {
            return ApiResponse.fail(resolveBusinessErrorCode(error), error.getMessage());
        }
    }

    @PostMapping("/walks/{walkId}/comments")
    public ApiResponse<CommunityCommentResponse> createComment(
            HttpServletRequest request,
            @PathVariable Long walkId,
            @Valid @RequestBody CommunityCommentCreateRequest body
    ) {
        try {
            Long userId = resolveRequiredUserId(request);
            return ApiResponse.success(communityService.createComment(walkId, userId, body));
        } catch (IllegalStateException error) {
            return ApiResponse.fail(resolveBusinessErrorCode(error), error.getMessage());
        }
    }

    @GetMapping("/feed/latest")
    public ApiResponse<List<CommunityWalkResponse>> latest(
            HttpServletRequest request,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize
    ) {
        return ApiResponse.success(communityService.latestFeed(resolveOptionalUserId(request), page, pageSize));
    }

    @GetMapping("/feed/hot")
    public ApiResponse<List<CommunityWalkResponse>> hot(
            HttpServletRequest request,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize
    ) {
        return ApiResponse.success(communityService.hotFeed(resolveOptionalUserId(request), page, pageSize));
    }

    @GetMapping("/feed/recommend")
    public ApiResponse<List<CommunityWalkResponse>> recommend(
            HttpServletRequest request,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize
    ) {
        return ApiResponse.success(communityService.recommendFeed(resolveOptionalUserId(request), page, pageSize));
    }

    @GetMapping("/me/liked")
    public ApiResponse<List<CommunityWalkResponse>> liked(
            HttpServletRequest request,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize
    ) {
        try {
            return ApiResponse.success(communityService.likedWalks(resolveRequiredUserId(request), page, pageSize));
        } catch (IllegalStateException error) {
            return ApiResponse.fail(resolveBusinessErrorCode(error), error.getMessage());
        }
    }

    @GetMapping("/me/favorited")
    public ApiResponse<List<CommunityWalkResponse>> favorited(
            HttpServletRequest request,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize
    ) {
        try {
            return ApiResponse.success(communityService.favoritedWalks(resolveRequiredUserId(request), page, pageSize));
        } catch (IllegalStateException error) {
            return ApiResponse.fail(resolveBusinessErrorCode(error), error.getMessage());
        }
    }

    @PostMapping("/walks/{walkId}/like")
    public ApiResponse<CommunityEngagementResponse> like(HttpServletRequest request, @PathVariable Long walkId) {
        try {
            return ApiResponse.success(communityService.likeWalk(walkId, resolveRequiredUserId(request)));
        } catch (IllegalStateException error) {
            return ApiResponse.fail(resolveBusinessErrorCode(error), error.getMessage());
        }
    }

    @DeleteMapping("/walks/{walkId}/like")
    public ApiResponse<CommunityEngagementResponse> unlike(HttpServletRequest request, @PathVariable Long walkId) {
        try {
            return ApiResponse.success(communityService.unlikeWalk(walkId, resolveRequiredUserId(request)));
        } catch (IllegalStateException error) {
            return ApiResponse.fail(resolveBusinessErrorCode(error), error.getMessage());
        }
    }

    @PostMapping("/walks/{walkId}/favorite")
    public ApiResponse<CommunityEngagementResponse> favorite(HttpServletRequest request, @PathVariable Long walkId) {
        try {
            return ApiResponse.success(communityService.favoriteWalk(walkId, resolveRequiredUserId(request)));
        } catch (IllegalStateException error) {
            return ApiResponse.fail(resolveBusinessErrorCode(error), error.getMessage());
        }
    }

    @DeleteMapping("/walks/{walkId}/favorite")
    public ApiResponse<CommunityEngagementResponse> unfavorite(HttpServletRequest request, @PathVariable Long walkId) {
        try {
            return ApiResponse.success(communityService.unfavoriteWalk(walkId, resolveRequiredUserId(request)));
        } catch (IllegalStateException error) {
            return ApiResponse.fail(resolveBusinessErrorCode(error), error.getMessage());
        }
    }

    private int resolveBusinessErrorCode(IllegalStateException error) {
        return switch (error.getMessage()) {
            case "login_required" -> 401;
            case "walk_not_found" -> 404;
            case "comment_not_found" -> 404;
            default -> 400;
        };
    }

    private Long resolveRequiredUserId(HttpServletRequest request) {
        Long userId = resolveOptionalUserId(request);
        if (userId == null || userId <= 0) {
            throw new IllegalStateException("login_required");
        }
        return userId;
    }

    private Long resolveOptionalUserId(HttpServletRequest request) {
        String token = extractBearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (token == null) {
            return null;
        }
        try {
            return authTokenService.parseAccessToken(token).userId();
        } catch (RuntimeException error) {
            return null;
        }
    }

    private String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return null;
        }
        if (!authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = authorizationHeader.substring(7).trim();
        return token.isBlank() ? null : token;
    }
}
