package com.liuliu.citywalk.controller;

import com.liuliu.citywalk.common.ApiResponse;
import com.liuliu.citywalk.model.dto.request.CreateWalkRequest;
import com.liuliu.citywalk.model.dto.response.OperationResultResponse;
import com.liuliu.citywalk.model.dto.response.WalkResponse;
import com.liuliu.citywalk.service.AuthTokenService;
import com.liuliu.citywalk.service.WalkService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/walks")
public class WalkController {

    private final WalkService walkService;
    private final AuthTokenService authTokenService;

    public WalkController(WalkService walkService, AuthTokenService authTokenService) {
        this.walkService = walkService;
        this.authTokenService = authTokenService;
    }

    @PostMapping
    public ApiResponse<WalkResponse> create(HttpServletRequest request, @Valid @RequestBody CreateWalkRequest body) {
        Long userId = resolveUserId(request);
        WalkResponse record = walkService.create(userId, body);
        return ApiResponse.success(record);
    }

    @GetMapping("/me")
    public ApiResponse<List<WalkResponse>> myWalks(HttpServletRequest request,
                                                   @RequestParam(defaultValue = "1") Integer page,
                                                   @RequestParam(defaultValue = "20") Integer pageSize) {
        Long userId = resolveUserId(request);
        return ApiResponse.success(walkService.listMyWalks(userId, pageSize));
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

    @DeleteMapping("/{walkId}")
    public ApiResponse<OperationResultResponse> delete(HttpServletRequest request, @PathVariable Long walkId) {
        try {
            Long userId = resolveUserId(request);
            return ApiResponse.success(walkService.deleteWalk(walkId, userId));
        } catch (IllegalStateException error) {
            int code = switch (error.getMessage()) {
                case "login_required" -> 401;
                case "walk_not_found" -> 404;
                case "walk_forbidden" -> 403;
                default -> 400;
            };
            return ApiResponse.fail(code, error.getMessage());
        }
    }

    private Long resolveUserId(HttpServletRequest request) {
        String token = extractBearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (token == null) {
            throw new IllegalStateException("login_required");
        }
        try {
            return authTokenService.parseAccessToken(token).userId();
        } catch (RuntimeException error) {
            throw new IllegalStateException("login_required");
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
