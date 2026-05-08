package com.liuliu.citywalk.service;

import com.liuliu.citywalk.config.WechatOpenProperties;
import com.liuliu.citywalk.model.dto.response.UserProfileResponse;
import com.liuliu.citywalk.model.dto.response.WechatCallbackResponse;
import com.liuliu.citywalk.model.dto.response.WechatLoginUrlResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WechatAuthService {

    private final WechatOpenProperties wechatOpenProperties;
    private final AuthTokenService authTokenService;
    private final Map<String, String> redirectUriCache = new ConcurrentHashMap<>();

    public WechatAuthService(WechatOpenProperties wechatOpenProperties, AuthTokenService authTokenService) {
        this.wechatOpenProperties = wechatOpenProperties;
        this.authTokenService = authTokenService;
    }

    public WechatLoginUrlResponse buildLoginUrl(String redirectUri) {
        String finalRedirectUri = (redirectUri == null || redirectUri.isBlank())
                ? wechatOpenProperties.getFrontendBaseUrl()
                : redirectUri;
        String state = "cw_" + System.currentTimeMillis();
        redirectUriCache.put(state, finalRedirectUri);

        String callbackUrl = URLEncoder.encode(wechatOpenProperties.getCallbackUrl(), StandardCharsets.UTF_8);
        String authUrl = "https://open.weixin.qq.com/connect/qrconnect"
                + "?appid=" + wechatOpenProperties.getAppId()
                + "&redirect_uri=" + callbackUrl
                + "&response_type=code"
                + "&scope=snsapi_login"
                + "&state=" + state
                + "#wechat_redirect";

        return new WechatLoginUrlResponse(authUrl);
    }

    public String handleCallback(String code, String state) {
        String frontendRedirectUri = redirectUriCache.getOrDefault(state, wechatOpenProperties.getFrontendBaseUrl());

        /*
         * 这里是后续接真实微信接口的地方：
         * 1. 用 code 换微信 access_token
         * 2. 再换 openid / unionid / 用户信息
         * 3. 查库或创建用户
         * 4. 生成系统自己的 JWT
         */
        WechatCallbackResponse response = mockWechatLoginSuccess(code);

        return UriComponentsBuilder.fromUriString(frontendRedirectUri)
                .queryParam("token", response.token())
                .queryParam("refreshToken", response.refreshToken())
                .build(true)
                .toUriString();
    }

    public UserProfileResponse loadCurrentUser(String authorizationHeader) {
        return new UserProfileResponse(1001L, "六六", "https://cdn.example.com/avatar.jpg", "");
    }

    private WechatCallbackResponse mockWechatLoginSuccess(String code) {
        Long userId = 1001L;
        UserProfileResponse user = new UserProfileResponse(
                userId,
                code == null || code.isBlank() ? "微信用户" : "微信用户_" + code.substring(0, Math.min(6, code.length())),
                "https://cdn.example.com/avatar.jpg",
                ""
        );

        return new WechatCallbackResponse(
                authTokenService.createAccessToken(userId),
                authTokenService.createRefreshToken(userId),
                user
        );
    }
}
