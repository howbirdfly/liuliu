package com.liuliu.citywalk.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.common.ApiResponse;
import com.liuliu.citywalk.service.AuthTokenService;
import com.liuliu.citywalk.service.MiniappSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

@Component
public class WebJwtInterceptor implements HandlerInterceptor {

    private final AuthTokenService authTokenService;
    private final MiniappSessionService miniappSessionService;
    private final ObjectMapper objectMapper;

    public WebJwtInterceptor(
            AuthTokenService authTokenService,
            MiniappSessionService miniappSessionService,
            ObjectMapper objectMapper
    ) {
        this.authTokenService = authTokenService;
        this.miniappSessionService = miniappSessionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod) || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String token = extractBearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (token == null) {
            writeUnauthorized(response, "login_required");
            return false;
        }

        try {
            AuthTokenService.TokenClaims claims = authTokenService.parseAccessToken(token);
            MiniappSessionService.StoredMiniappUser user = miniappSessionService.resolveUserByToken(token);
            if (user == null || user.isGuest() || !claims.userId().equals(user.id())) {
                writeUnauthorized(response, "invalid_token");
                return false;
            }
            return true;
        } catch (Exception error) {
            writeUnauthorized(response, "invalid_token");
            return false;
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

    private void writeUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiResponse.fail(401, message));
    }
}
