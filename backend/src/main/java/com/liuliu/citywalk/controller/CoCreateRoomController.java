package com.liuliu.citywalk.controller;

import com.liuliu.citywalk.common.ApiResponse;
import com.liuliu.citywalk.model.dto.request.CreateCoCreateRoomRequest;
import com.liuliu.citywalk.model.dto.request.JoinCoCreateRoomRequest;
import com.liuliu.citywalk.model.dto.request.UpdateCoCreateRoomStateRequest;
import com.liuliu.citywalk.model.dto.request.UpdateCoCreateRoomThemeRequest;
import com.liuliu.citywalk.model.dto.response.CoCreateRoomResponse;
import com.liuliu.citywalk.service.CoCreateRoomService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/co-create")
public class CoCreateRoomController {

    private final CoCreateRoomService coCreateRoomService;

    public CoCreateRoomController(CoCreateRoomService coCreateRoomService) {
        this.coCreateRoomService = coCreateRoomService;
    }

    @PostMapping("/rooms")
    public ApiResponse<CoCreateRoomResponse> create(HttpServletRequest request, @Valid @RequestBody CreateCoCreateRoomRequest body) {
        try {
            return ApiResponse.success(coCreateRoomService.createRoom(request.getHeader(HttpHeaders.AUTHORIZATION), body));
        } catch (IllegalStateException error) {
            return ApiResponse.fail("login_required".equals(error.getMessage()) ? 401 : 400, error.getMessage());
        }
    }

    @PostMapping("/rooms/join")
    public ApiResponse<CoCreateRoomResponse> join(HttpServletRequest request, @Valid @RequestBody JoinCoCreateRoomRequest body) {
        try {
            return ApiResponse.success(coCreateRoomService.joinRoom(request.getHeader(HttpHeaders.AUTHORIZATION), body));
        } catch (IllegalStateException error) {
            return ApiResponse.fail("login_required".equals(error.getMessage()) ? 401 : 400, error.getMessage());
        }
    }

    @GetMapping("/rooms/{roomCode}")
    public ApiResponse<CoCreateRoomResponse> detail(HttpServletRequest request, @PathVariable String roomCode) {
        try {
            return ApiResponse.success(coCreateRoomService.getRoom(request.getHeader(HttpHeaders.AUTHORIZATION), roomCode));
        } catch (IllegalStateException error) {
            return ApiResponse.fail("login_required".equals(error.getMessage()) ? 401 : 400, error.getMessage());
        }
    }

    @GetMapping("/rooms/current")
    public ApiResponse<CoCreateRoomResponse> current(HttpServletRequest request) {
        try {
            return ApiResponse.success(coCreateRoomService.getCurrentRoom(request.getHeader(HttpHeaders.AUTHORIZATION)));
        } catch (IllegalStateException error) {
            if ("room_not_found".equals(error.getMessage())) {
                return ApiResponse.success(null);
            }
            return ApiResponse.fail("login_required".equals(error.getMessage()) ? 401 : 400, error.getMessage());
        }
    }

    @PutMapping("/rooms/{roomCode}/state")
    public ApiResponse<CoCreateRoomResponse> updateState(HttpServletRequest request,
                                                         @PathVariable String roomCode,
                                                         @Valid @RequestBody UpdateCoCreateRoomStateRequest body) {
        try {
            return ApiResponse.success(coCreateRoomService.updateRoomState(request.getHeader(HttpHeaders.AUTHORIZATION), roomCode, body));
        } catch (IllegalStateException error) {
            return ApiResponse.fail("login_required".equals(error.getMessage()) ? 401 : 400, error.getMessage());
        }
    }

    @PutMapping("/rooms/{roomCode}/theme")
    public ApiResponse<CoCreateRoomResponse> updateTheme(HttpServletRequest request,
                                                         @PathVariable String roomCode,
                                                         @Valid @RequestBody UpdateCoCreateRoomThemeRequest body) {
        try {
            return ApiResponse.success(coCreateRoomService.updateRoomTheme(request.getHeader(HttpHeaders.AUTHORIZATION), roomCode, body));
        } catch (IllegalStateException error) {
            return ApiResponse.fail("login_required".equals(error.getMessage()) ? 401 : 400, error.getMessage());
        }
    }

    @DeleteMapping("/rooms/{roomCode}")
    public ApiResponse<Boolean> leave(HttpServletRequest request, @PathVariable String roomCode) {
        try {
            coCreateRoomService.leaveRoom(request.getHeader(HttpHeaders.AUTHORIZATION), roomCode);
            return ApiResponse.success(true);
        } catch (IllegalStateException error) {
            return ApiResponse.fail("login_required".equals(error.getMessage()) ? 401 : 400, error.getMessage());
        }
    }
}
