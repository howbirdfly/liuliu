package com.liuliu.citywalk.service;

import com.liuliu.citywalk.context.MiniappUserContext;
import com.liuliu.citywalk.model.dto.response.MiniappSyncUserResponse;
import com.liuliu.citywalk.model.dto.response.MiniappUserResponse;
import com.liuliu.citywalk.model.dto.response.UserProfileResponse;
import com.liuliu.citywalk.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserSessionService {

    private final AuthTokenService authTokenService;
    private final UserRepository userRepository;
    private final StoredUser guestUser = new StoredUser(0L, "guest", "游客", "", "guest", 0L, 0L);

    public UserSessionService(
            AuthTokenService authTokenService,
            UserRepository userRepository
    ) {
        this.authTokenService = authTokenService;
        this.userRepository = userRepository;
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
        UserRepository.UserRecord user = userRepository.findByOpenid(openId)
                .map(item -> userRepository.updateProfileAndLogin(item.id(), normalizeNickName(nickName), normalizeAvatar(avatarUrl)))
                .orElseGet(() -> userRepository.create(openId, normalizeNickName(nickName), normalizeAvatar(avatarUrl)));

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

    @Transactional
    public UserProfileResponse updateProfile(String authorizationHeader, String nickName, String avatarUrl) {
        StoredUser currentUser = resolveUser(authorizationHeader);
        if (currentUser == null || currentUser.isGuest()) {
            throw new IllegalStateException("login_required");
        }

        UserRepository.UserRecord updatedUser = userRepository.updateProfile(
                currentUser.id(),
                normalizeNickName(nickName),
                normalizeAvatar(avatarUrl)
        );
        return new UserProfileResponse(
                updatedUser.id(),
                updatedUser.nickname(),
                updatedUser.avatarUrl()
        );
    }

    public StoredUser resolveUser(String authorizationHeader) {
        return resolveUserByToken(extractToken(authorizationHeader));
    }

    public StoredUser resolveUserByToken(String token) {
        if (token == null || token.isBlank()) {
            return guestUser;
        }

        try {
            AuthTokenService.TokenClaims claims = authTokenService.parseAccessToken(token);
            return userRepository.findById(claims.userId())
                    .map(this::toStoredUser)
                    .orElse(guestUser);
        } catch (Exception error) {
            return guestUser;
        }
    }

    public StoredUser loadUserById(Long userId) {
        if (userId == null || userId <= 0) {
            return guestUser;
        }
        return userRepository.findById(userId)
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

    private StoredUser toStoredUser(UserRepository.UserRecord user) {
        return new StoredUser(
                user.id(),
                user.openid(),
                user.nickname(),
                user.avatarUrl(),
                user.role(),
                user.createdAt(),
                user.lastLoginAt()
        );
    }

    private MiniappUserResponse toResponse(StoredUser user) {
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

    private MiniappUserResponse toResponse(UserRepository.UserRecord user) {
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

    public record StoredUser(
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
