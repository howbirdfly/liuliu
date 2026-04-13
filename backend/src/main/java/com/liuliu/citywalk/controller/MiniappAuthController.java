package com.liuliu.citywalk.controller;

import com.liuliu.citywalk.common.ApiResponse;
import com.liuliu.citywalk.model.dto.request.MiniappSyncUserRequest;
import com.liuliu.citywalk.model.dto.response.MiniappSyncUserResponse;
import com.liuliu.citywalk.model.dto.response.MiniappUserResponse;
import com.liuliu.citywalk.service.UserSessionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/miniapp/auth")
public class MiniappAuthController {

    private final UserSessionService userSessionService;

    public MiniappAuthController(UserSessionService userSessionService) {
        this.userSessionService = userSessionService;
    }

    @PostMapping("/sync-user")
    public ApiResponse<MiniappSyncUserResponse> syncUser(@RequestBody MiniappSyncUserRequest request) {
        return ApiResponse.success(userSessionService.syncUser(request.code(), request.nickName(), request.avatarUrl()));
    }

    @GetMapping("/me")
    public ApiResponse<MiniappUserResponse> currentUser() {
        return ApiResponse.success(userSessionService.currentUser());
    }
}
