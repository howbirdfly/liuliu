package com.liuliu.citywalk.controller;

import com.liuliu.citywalk.common.ApiResponse;
import com.liuliu.citywalk.model.dto.response.NotificationUnreadCountResponse;
import com.liuliu.citywalk.model.dto.response.UserNotificationResponse;
import com.liuliu.citywalk.service.NotificationService;
import com.liuliu.citywalk.service.UserSessionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final UserSessionService userSessionService;

    public NotificationController(NotificationService notificationService, UserSessionService userSessionService) {
        this.notificationService = notificationService;
        this.userSessionService = userSessionService;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam(required = false) String token,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        UserSessionService.StoredUser currentUser = resolveStreamUser(token, authorizationHeader);
        if (currentUser == null || currentUser.isGuest()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "login_required");
        }
        return notificationService.subscribe(currentUser.id());
    }

    @GetMapping
    public ApiResponse<List<UserNotificationResponse>> list(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize
    ) {
        try {
            Long userId = resolveRequiredUserId(authorizationHeader);
            return ApiResponse.success(notificationService.listNotifications(userId, page, pageSize));
        } catch (IllegalStateException error) {
            return ApiResponse.fail(resolveBusinessErrorCode(error), error.getMessage());
        }
    }

    @GetMapping("/unread-count")
    public ApiResponse<NotificationUnreadCountResponse> unreadCount(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        try {
            Long userId = resolveRequiredUserId(authorizationHeader);
            return ApiResponse.success(notificationService.unreadCount(userId));
        } catch (IllegalStateException error) {
            return ApiResponse.fail(resolveBusinessErrorCode(error), error.getMessage());
        }
    }

    @PostMapping("/{notificationId}/read")
    public ApiResponse<Boolean> markRead(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @PathVariable Long notificationId
    ) {
        try {
            Long userId = resolveRequiredUserId(authorizationHeader);
            notificationService.markRead(notificationId, userId);
            return ApiResponse.success(Boolean.TRUE);
        } catch (IllegalStateException error) {
            return ApiResponse.fail(resolveBusinessErrorCode(error), error.getMessage());
        }
    }

    @PostMapping("/read-all")
    public ApiResponse<Boolean> markAllRead(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        try {
            Long userId = resolveRequiredUserId(authorizationHeader);
            notificationService.markAllRead(userId);
            return ApiResponse.success(Boolean.TRUE);
        } catch (IllegalStateException error) {
            return ApiResponse.fail(resolveBusinessErrorCode(error), error.getMessage());
        }
    }

    private int resolveBusinessErrorCode(IllegalStateException error) {
        if ("login_required".equals(error.getMessage())) {
            return 401;
        }
        return 400;
    }

    private Long resolveRequiredUserId(String authorizationHeader) {
        UserSessionService.StoredUser currentUser = userSessionService.resolveUser(authorizationHeader);
        if (currentUser == null || currentUser.isGuest()) {
            throw new IllegalStateException("login_required");
        }
        return currentUser.id();
    }

    private UserSessionService.StoredUser resolveStreamUser(String token, String authorizationHeader) {
        if (token != null && !token.isBlank()) {
            return userSessionService.resolveUserByToken(token.trim());
        }
        return userSessionService.resolveUser(authorizationHeader);
    }
}
