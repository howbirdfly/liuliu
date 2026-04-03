package com.liuliu.citywalk.service;

import com.liuliu.citywalk.context.MiniappUserContext;
import com.liuliu.citywalk.model.dto.response.MiniappSyncUserResponse;
import com.liuliu.citywalk.model.dto.response.MiniappUserResponse;
import com.liuliu.citywalk.repository.MiniappUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MiniappSessionService {

    private final AuthTokenService authTokenService;
    private final MiniappUserRepository miniappUserRepository;
    private final StoredMiniappUser guestUser = new StoredMiniappUser(0L, "guest", "游客", "", "guest", 0L, 0L);

    public MiniappSessionService(
            AuthTokenService authTokenService,
            MiniappUserRepository miniappUserRepository
    ) {
        this.authTokenService = authTokenService;
        this.miniappUserRepository = miniappUserRepository;
    }

    @Transactional
    public MiniappSyncUserResponse syncUser(String code, String nickName, String avatarUrl) {
        return syncUser(code, nickName, avatarUrl, "miniapp");
    }

    @Transactional
    public MiniappSyncUserResponse syncWebUser(String code, String nickName, String avatarUrl) {
        return syncUser(code, nickName, avatarUrl, "web");
    }

    @Transactional
    public MiniappSyncUserResponse syncUser(String code, String nickName, String avatarUrl, String clientType) {
        String openId = buildOpenId(code, nickName);
        MiniappUserRepository.MiniappUserRecord user = miniappUserRepository.findByOpenid(openId)
                .map(item -> miniappUserRepository.updateProfileAndLogin(item.id(), normalizeNickName(nickName), normalizeAvatar(avatarUrl)))
                .orElseGet(() -> miniappUserRepository.create(openId, normalizeNickName(nickName), normalizeAvatar(avatarUrl)));

        String token = authTokenService.createAccessToken(user.id());
        String refreshToken = authTokenService.createRefreshToken(user.id());

        return new MiniappSyncUserResponse(token, refreshToken, authTokenService.getAccessExpireSeconds(), toResponse(user), user.openid());
    }

    public MiniappUserResponse currentUser(String authorizationHeader) {
        return toResponse(resolveUser(authorizationHeader));
    }

    public MiniappUserResponse currentUser() {
        return toResponse(loadUserById(MiniappUserContext.getCurrentUserId()));
    }

    public StoredMiniappUser resolveUser(String authorizationHeader) {
        return resolveUserByToken(extractToken(authorizationHeader));
    }

    public StoredMiniappUser resolveUserByToken(String token) {
        if (token == null || token.isBlank()) {
            return guestUser;
        }

        try {
            AuthTokenService.TokenClaims claims = authTokenService.parseAccessToken(token);
            return miniappUserRepository.findById(claims.userId())
                    .map(this::toStoredUser)
                    .orElse(guestUser);
        } catch (Exception error) {
            return guestUser;
        }
    }

    public StoredMiniappUser loadUserById(Long userId) {
        if (userId == null || userId <= 0) {
            return guestUser;
        }
        return miniappUserRepository.findById(userId)
                .map(this::toStoredUser)
                .orElse(guestUser);
    }

    private String extractToken(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return null;
        }
        String token = authorizationHeader.replace("Bearer ", "").trim();
        return token.isBlank() ? null : token;
    }

    private String buildOpenId(String code, String nickName) {
        String seed = code;
        if (seed == null || seed.isBlank()) {
            seed = nickName;
        }
        if (seed == null || seed.isBlank()) {
            seed = "guest-" + System.currentTimeMillis();
        }
        return "wx_" + Integer.toUnsignedString(seed.hashCode());
    }

    private String normalizeNickName(String nickName) {
        return nickName == null || nickName.isBlank() ? "微信用户" : nickName.trim();
    }

    private String normalizeAvatar(String avatarUrl) {
        return avatarUrl == null ? "" : avatarUrl.trim();
    }

    private String normalizeClientType(String clientType) {
        if (clientType == null || clientType.isBlank()) {
            return "miniapp";
        }
        return clientType.trim();
    }

    private StoredMiniappUser toStoredUser(MiniappUserRepository.MiniappUserRecord user) {
        return new StoredMiniappUser(
                user.id(),
                user.openid(),
                user.nickname(),
                user.avatarUrl(),
                user.role(),
                user.createdAt(),
                user.lastLoginAt()
        );
    }

    private MiniappUserResponse toResponse(StoredMiniappUser user) {
        return user == null ? null : new MiniappUserResponse(
                user.id(),
                user.openid(),
                user.nickName(),
                user.avatarUrl(),
                user.role(),
                user.createdAt(),
                user.lastLoginAt()
        );
    }

    private MiniappUserResponse toResponse(MiniappUserRepository.MiniappUserRecord user) {
        return user == null ? null : new MiniappUserResponse(
                user.id(),
                user.openid(),
                user.nickname(),
                user.avatarUrl(),
                user.role(),
                user.createdAt(),
                user.lastLoginAt()
        );
    }

    public record StoredMiniappUser(
            Long id,
            String openid,
            String nickName,
            String avatarUrl,
            String role,
            Long createdAt,
            Long lastLoginAt
    ) {
        public boolean isGuest() {
            return id == null || id <= 0;
        }
    }
}
