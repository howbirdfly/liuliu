package com.liuliu.citywalk.controller;

import com.liuliu.citywalk.common.ApiResponse;
import com.liuliu.citywalk.context.MiniappUserContext;
import com.liuliu.citywalk.model.dto.request.MiniappCreateWalkRequest;
import com.liuliu.citywalk.model.dto.response.MiniappCreateWalkResponse;
import com.liuliu.citywalk.model.dto.response.MiniappWalkDetailResponse;
import com.liuliu.citywalk.model.dto.response.MiniappWalkListResponse;
import com.liuliu.citywalk.model.dto.response.MiniappWalkRecordResponse;
import com.liuliu.citywalk.service.UserSessionService;
import com.liuliu.citywalk.service.MiniappWalkService;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/miniapp/walks")
public class MiniappWalkController {

    private final MiniappWalkService miniappWalkService;
    private final UserSessionService userSessionService;

    public MiniappWalkController(MiniappWalkService miniappWalkService, UserSessionService userSessionService) {
        this.miniappWalkService = miniappWalkService;
        this.userSessionService = userSessionService;
    }

    @PostMapping
    public ApiResponse<MiniappCreateWalkResponse> create(@RequestBody MiniappCreateWalkRequest request) {
        Long userId = MiniappUserContext.getCurrentUserId();
        if (userId == null || userId <= 0) {
            return ApiResponse.fail(401, "login_required");
        }
        MiniappWalkRecordResponse record = miniappWalkService.create(userId, request);
        return ApiResponse.success(new MiniappCreateWalkResponse(true, record._id()));
    }

    @GetMapping("/me")
    public ApiResponse<MiniappWalkListResponse> myWalks(@RequestParam(defaultValue = "20") Integer limit) {
        Long userId = MiniappUserContext.getCurrentUserId();
        int finalLimit = Math.max(1, Math.min(limit, 50));
        return ApiResponse.success(new MiniappWalkListResponse(miniappWalkService.listMyWalks(userId, finalLimit)));
    }

    @GetMapping("/public")
    public ApiResponse<MiniappWalkListResponse> publicWalks(@RequestParam(defaultValue = "20") Integer limit) {
        int finalLimit = Math.max(1, Math.min(limit, 50));
        return ApiResponse.success(new MiniappWalkListResponse(miniappWalkService.listPublicWalks(finalLimit)));
    }

    @GetMapping("/{id}")
    public ApiResponse<MiniappWalkDetailResponse> detail(
            @PathVariable String id,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        UserSessionService.StoredUser user = userSessionService.resolveUser(authorizationHeader);
        return ApiResponse.success(new MiniappWalkDetailResponse(miniappWalkService.getDetail(id, user.id())));
    }
}
