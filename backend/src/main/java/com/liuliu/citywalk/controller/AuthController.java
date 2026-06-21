package com.liuliu.citywalk.controller;

import com.liuliu.citywalk.common.ApiResponse;
import com.liuliu.citywalk.context.BaseContext;
import com.liuliu.citywalk.model.dto.request.EmailCodeRequest;
import com.liuliu.citywalk.model.dto.request.EmailLoginRequest;
import com.liuliu.citywalk.model.dto.request.EmailRegisterRequest;
import com.liuliu.citywalk.model.dto.request.EmailResetPasswordRequest;
import com.liuliu.citywalk.model.dto.request.LoginRequest;
import com.liuliu.citywalk.model.dto.request.UpdateUserProfileRequest;
import com.liuliu.citywalk.model.dto.response.LoginResponse;
import com.liuliu.citywalk.model.dto.response.UserProfileResponse;
import com.liuliu.citywalk.service.AuthTokenService;
import com.liuliu.citywalk.service.EmailAuthService;
import com.liuliu.citywalk.service.UserSessionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthTokenService authTokenService;
    private final UserSessionService userSessionService;
    private final EmailAuthService emailAuthService;

    public AuthController(
            AuthTokenService authTokenService,
            UserSessionService userSessionService,
            EmailAuthService emailAuthService
    ) {
        this.authTokenService = authTokenService;
        this.userSessionService = userSessionService;
        this.emailAuthService = emailAuthService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        Long userId = 1001L;
        UserProfileResponse user = new UserProfileResponse(userId, "六六", "https://cdn.example.com/avatar.jpg", "");
        LoginResponse response = new LoginResponse(
                authTokenService.createAccessToken(userId),
                authTokenService.createRefreshToken(userId),
                authTokenService.getAccessExpireSeconds(),
                user
        );
        return ApiResponse.success(response);
    }

    @PostMapping("/mock-login")
    public ApiResponse<LoginResponse> mockLogin() {
        Long userId = 1001L;
        UserProfileResponse user = new UserProfileResponse(userId, "本地测试用户", "https://cdn.example.com/avatar.jpg", "");
        LoginResponse response = new LoginResponse(
                authTokenService.createAccessToken(userId),
                authTokenService.createRefreshToken(userId),
                authTokenService.getAccessExpireSeconds(),
                user
        );
        return ApiResponse.success(response);
    }

    @PostMapping("/email/send-code")
    public ApiResponse<Boolean> sendEmailCode(@Valid @RequestBody EmailCodeRequest request) {
        try {
            emailAuthService.sendVerificationCode(request.email(), request.scene());
            return ApiResponse.success(Boolean.TRUE);
        } catch (IllegalStateException error) {
            return ApiResponse.fail(400, error.getMessage());
        } catch (RuntimeException error) {
            return ApiResponse.fail(500, "email_send_failed");
        }
    }

    @PostMapping("/email/register")
    public ApiResponse<LoginResponse> emailRegister(@Valid @RequestBody EmailRegisterRequest request) {
        try {
            return ApiResponse.success(emailAuthService.register(request.email(), request.password(), request.code()));
        } catch (IllegalStateException error) {
            return ApiResponse.fail(400, error.getMessage());
        }
    }

    @PostMapping("/email/login")
    public ApiResponse<LoginResponse> emailLogin(@Valid @RequestBody EmailLoginRequest request) {
        try {
            return ApiResponse.success(emailAuthService.login(request.email(), request.password()));
        } catch (IllegalStateException error) {
            return ApiResponse.fail(400, error.getMessage());
        }
    }

    @PostMapping("/email/reset-password")
    public ApiResponse<Boolean> resetPassword(@Valid @RequestBody EmailResetPasswordRequest request) {
        try {
            emailAuthService.resetPassword(request.email(), request.password(), request.code());
            return ApiResponse.success(Boolean.TRUE);
        } catch (IllegalStateException error) {
            return ApiResponse.fail(400, error.getMessage());
        }
    }

    @GetMapping("/me")
    public ApiResponse<UserProfileResponse> currentUser() {
        UserSessionService.StoredUser currentUser = userSessionService.loadUserById(BaseContext.requireCurrentUserId());
        if (currentUser == null) {
            return ApiResponse.fail(404, "user_not_found");
        }
        return ApiResponse.success(new UserProfileResponse(
                currentUser.id(),
                currentUser.nickName(),
                currentUser.avatarUrl(),
                currentUser.bio()
        ));
    }

    @PutMapping("/profile")
    public ApiResponse<UserProfileResponse> updateProfile(
            @Valid @RequestBody UpdateUserProfileRequest request
    ) {
        try {
            UserProfileResponse response = userSessionService.updateProfileByUserId(
                    BaseContext.requireCurrentUserId(),
                    request.nickname(),
                    request.avatar(),
                    request.bio()
            );
            return ApiResponse.success(response);
        } catch (IllegalStateException error) {
            return ApiResponse.fail(400, error.getMessage());
        }
    }

    @PostMapping("/logout")
    public ApiResponse<Boolean> logout() {
        return ApiResponse.success(Boolean.TRUE);
    }

    @DeleteMapping("/account")
    public ApiResponse<Boolean> deleteAccount() {
        try {
            userSessionService.deleteCurrentUserByUserId(BaseContext.requireCurrentUserId());
            return ApiResponse.success(Boolean.TRUE);
        } catch (IllegalStateException error) {
            return ApiResponse.fail(400, error.getMessage());
        }
    }
}
