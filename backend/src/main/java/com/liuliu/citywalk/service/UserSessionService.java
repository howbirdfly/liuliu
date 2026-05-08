package com.liuliu.citywalk.service;

import com.liuliu.citywalk.context.MiniappUserContext;
import com.liuliu.citywalk.mapper.UserMapper;
import com.liuliu.citywalk.mapper.entity.UserEntity;
import com.liuliu.citywalk.model.dto.response.MiniappSyncUserResponse;
import com.liuliu.citywalk.model.dto.response.MiniappUserResponse;
import com.liuliu.citywalk.model.dto.response.UserProfileResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Optional;

@Service
public class UserSessionService {

    private final AuthTokenService authTokenService;
    private final UserMapper userMapper;
    private final StoredUser guestUser = new StoredUser(0L, "guest", "游客", "", "", "guest", 0L, 0L);

    public UserSessionService(
            AuthTokenService authTokenService,
            UserMapper userMapper
    ) {
        this.authTokenService = authTokenService;
        this.userMapper = userMapper;
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
        UserRecord user = findByOpenid(openId)
                .map(item -> updateProfileAndLogin(item.id(), normalizeNickName(nickName), normalizeAvatar(avatarUrl), clientType))
                .orElseGet(() -> createUser(openId, normalizeNickName(nickName), normalizeAvatar(avatarUrl), clientType));

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
    public UserProfileResponse updateProfile(String authorizationHeader, String nickName, String avatarUrl, String bio) {
        StoredUser currentUser = resolveUser(authorizationHeader);
        if (currentUser == null || currentUser.isGuest()) {
            throw new IllegalStateException("login_required");
        }

        UserRecord updatedUser = updateProfile(
                currentUser.id(),
                normalizeNickName(nickName),
                normalizeAvatar(avatarUrl),
                normalizeBio(bio)
        );
        return new UserProfileResponse(
                updatedUser.id(),
                updatedUser.nickname(),
                updatedUser.avatarUrl(),
                updatedUser.bio()
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
            return findById(claims.userId())
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
        return findById(userId)
                .map(this::toStoredUser)
                .orElse(guestUser);
    }

    private Optional<UserRecord> findByOpenid(String openid) {
        return Optional.ofNullable(userMapper.findByOpenid(openid)).map(this::toRecord);
    }

    private Optional<UserRecord> findById(Long id) {
        return Optional.ofNullable(userMapper.findById(id)).map(this::toRecord);
    }

    private UserRecord createUser(String openid, String nickname, String avatarUrl, String clientType) {
        if ("web".equalsIgnoreCase(normalizeClientType(clientType))) {
            userMapper.insertWebUser(openid, nickname, avatarUrl, "");
        } else {
            userMapper.insertMiniappUser(openid, nickname, avatarUrl, "");
        }
        return findByOpenid(openid).orElseThrow(() -> new IllegalStateException("created_user_not_found"));
    }

    private UserRecord updateProfileAndLogin(Long id, String nickname, String avatarUrl, String clientType) {
        userMapper.updateProfileAndLogin(id, nickname, avatarUrl, normalizeClientType(clientType));
        return findById(id).orElseThrow(() -> new IllegalStateException("updated_user_not_found"));
    }

    private UserRecord updateProfile(Long id, String nickname, String avatarUrl, String bio) {
        userMapper.updateProfile(id, nickname, avatarUrl, bio);
        return findById(id).orElseThrow(() -> new IllegalStateException("updated_user_not_found"));
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

    private String normalizeBio(String bio) {
        return bio == null ? "" : bio.trim();
    }

    private String normalizeClientType(String clientType) {
        if (clientType == null || clientType.isBlank()) {
            return "miniapp";
        }
        return clientType.trim();
    }

    private StoredUser toStoredUser(UserRecord user) {
        return new StoredUser(
                user.id(),
                user.openid(),
                user.nickname(),
                user.avatarUrl(),
                user.bio(),
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

    private MiniappUserResponse toResponse(UserRecord user) {
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

    private UserRecord toRecord(UserEntity user) {
        return new UserRecord(
                user.getId(),
                user.getOpenid(),
                user.getNickname(),
                user.getAvatarUrl(),
                user.getBio(),
                user.getRole(),
                toEpochMilli(user.getCreatedAt()),
                toEpochMilli(user.getLastLoginAt())
        );
    }

    private static Long toEpochMilli(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toEpochMilli();
    }

    private record UserRecord(
            Long id,
            String openid,
            String nickname,
            String avatarUrl,
            String bio,
            String role,
            Long createdAt,
            Long lastLoginAt
    ) {
    }

    public record StoredUser(
            Long id,
            String openid,
            String nickName,
            String avatarUrl,
            String bio,
            String role,
            Long createdAt,
            Long lastLoginAt
    ) {
        public boolean isGuest() {
            return id == null || id <= 0;
        }
    }
}
