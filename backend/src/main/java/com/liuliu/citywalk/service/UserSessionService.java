package com.liuliu.citywalk.service;

import com.liuliu.citywalk.mapper.EmailVerificationMapper;
import com.liuliu.citywalk.mapper.UploadedFileMapper;
import com.liuliu.citywalk.mapper.UserCredentialMapper;
import com.liuliu.citywalk.mapper.UserMapper;
import com.liuliu.citywalk.mapper.WalkInteractionMapper;
import com.liuliu.citywalk.mapper.entity.UserCredentialEntity;
import com.liuliu.citywalk.mapper.entity.UserEntity;
import com.liuliu.citywalk.model.dto.response.UserProfileResponse;
import com.liuliu.citywalk.util.AliOssUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class UserSessionService {

    private final AuthTokenService authTokenService;
    private final UserMapper userMapper;
    private final UserCredentialMapper userCredentialMapper;
    private final EmailVerificationMapper emailVerificationMapper;
    private final UploadedFileMapper uploadedFileMapper;
    private final WalkInteractionMapper walkInteractionMapper;
    private final AliOssUtil aliOssUtil;

    public UserSessionService(
            AuthTokenService authTokenService,
            UserMapper userMapper,
            UserCredentialMapper userCredentialMapper,
            EmailVerificationMapper emailVerificationMapper,
            UploadedFileMapper uploadedFileMapper,
            WalkInteractionMapper walkInteractionMapper,
            AliOssUtil aliOssUtil
    ) {
        this.authTokenService = authTokenService;
        this.userMapper = userMapper;
        this.userCredentialMapper = userCredentialMapper;
        this.emailVerificationMapper = emailVerificationMapper;
        this.uploadedFileMapper = uploadedFileMapper;
        this.walkInteractionMapper = walkInteractionMapper;
        this.aliOssUtil = aliOssUtil;
    }

    @Transactional
    public UserProfileResponse updateProfileByUserId(Long userId, String nickName, String avatarUrl, String bio) {
        if (userId == null || userId <= 0) {
            throw new IllegalStateException("login_required");
        }

        UserRecord updatedUser = updateProfile(
                userId,
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

    @Transactional
    public void deleteCurrentUserByUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalStateException("login_required");
        }

        UserCredentialEntity credential = userCredentialMapper.findByUserId(userId);

        walkInteractionMapper.deleteLikesByUserId(userId);
        walkInteractionMapper.deleteFavoritesByUserId(userId);
        walkInteractionMapper.recomputeLikeCounts();
        walkInteractionMapper.recomputeFavoriteCounts();

        List<String> fileKeys = uploadedFileMapper.listFileKeysByUserId(userId);
        for (String fileKey : fileKeys) {
            if (fileKey != null && !fileKey.isBlank()) {
                aliOssUtil.delete(fileKey.trim());
            }
        }

        uploadedFileMapper.deleteByUserId(userId);
        userCredentialMapper.deleteByUserId(userId);

        if (credential != null && credential.getEmail() != null && !credential.getEmail().isBlank()) {
            emailVerificationMapper.deleteByEmail(credential.getEmail().trim().toLowerCase(Locale.ROOT));
        }

        int deletedRows = userMapper.deleteById(userId);
        if (deletedRows <= 0) {
            throw new IllegalStateException("user_not_found");
        }
    }

    public StoredUser resolveUser(String authorizationHeader) {
        return resolveUserByToken(extractToken(authorizationHeader));
    }

    public StoredUser resolveUserByToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }

        try {
            AuthTokenService.TokenClaims claims = authTokenService.parseAccessToken(token);
            return findById(claims.userId())
                    .map(this::toStoredUser)
                    .orElse(null);
        } catch (Exception error) {
            return null;
        }
    }

    public StoredUser loadUserById(Long userId) {
        if (userId == null || userId <= 0) {
            return null;
        }
        return findById(userId)
                .map(this::toStoredUser)
                .orElse(null);
    }

    private Optional<UserRecord> findById(Long id) {
        return Optional.ofNullable(userMapper.findById(id)).map(this::toRecord);
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

    private String normalizeNickName(String nickName) {
        return nickName == null || nickName.isBlank() ? "" : nickName.trim();
    }

    private String normalizeAvatar(String avatarUrl) {
        return avatarUrl == null ? "" : avatarUrl.trim();
    }

    private String normalizeBio(String bio) {
        return bio == null ? "" : bio.trim();
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
