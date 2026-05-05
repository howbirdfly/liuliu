package com.liuliu.citywalk.service;

import com.liuliu.citywalk.mapper.EmailVerificationMapper;
import com.liuliu.citywalk.mapper.UserCredentialMapper;
import com.liuliu.citywalk.mapper.UserMapper;
import com.liuliu.citywalk.mapper.entity.EmailVerificationEntity;
import com.liuliu.citywalk.mapper.entity.UserCredentialEntity;
import com.liuliu.citywalk.mapper.entity.UserEntity;
import com.liuliu.citywalk.model.dto.response.LoginResponse;
import com.liuliu.citywalk.model.dto.response.UserProfileResponse;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

@Service
public class EmailAuthService {

    private static final int SALT_LENGTH = 16;
    private static final long CODE_COOLDOWN_SECONDS = 60;
    private static final long CODE_EXPIRE_MINUTES = 30;

    private final AuthTokenService authTokenService;
    private final UserCredentialMapper userCredentialMapper;
    private final EmailVerificationMapper emailVerificationMapper;
    private final UserMapper userMapper;
    private final EmailSender emailSender;

    public EmailAuthService(
            AuthTokenService authTokenService,
            UserCredentialMapper userCredentialMapper,
            EmailVerificationMapper emailVerificationMapper,
            UserMapper userMapper,
            EmailSender emailSender
    ) {
        this.authTokenService = authTokenService;
        this.userCredentialMapper = userCredentialMapper;
        this.emailVerificationMapper = emailVerificationMapper;
        this.userMapper = userMapper;
        this.emailSender = emailSender;
    }

    public void sendVerificationCode(String email, String scene) {
        String normalizedEmail = normalizeEmail(email);
        ensureValidEmail(normalizedEmail);
        String normalizedScene = normalizeScene(scene);

        UserCredentialEntity existingCredential = userCredentialMapper.findByEmail(normalizedEmail);
        if ("register".equals(normalizedScene) && existingCredential != null) {
            throw new IllegalStateException("email_already_registered");
        }
        if ("reset".equals(normalizedScene) && existingCredential == null) {
            throw new IllegalStateException("email_not_registered");
        }

        EmailVerificationEntity latest = emailVerificationMapper.findLatest(normalizedEmail);
        if (latest != null && latest.getCreatedAt() != null
                && latest.getCreatedAt().toInstant().isAfter(Instant.now().minusSeconds(CODE_COOLDOWN_SECONDS))) {
            throw new IllegalStateException("code_send_too_frequent");
        }

        String code = generateCode();
        String codeHash = hashVerificationCode(code);
        emailVerificationMapper.insertRecord(normalizedEmail, codeHash, Timestamp.from(Instant.now().plus(CODE_EXPIRE_MINUTES, ChronoUnit.MINUTES)));
        emailSender.sendVerificationCode(normalizedEmail, code);
    }

    public LoginResponse register(String email, String password, String code) {
        String normalizedEmail = normalizeEmail(email);
        ensureValidEmail(normalizedEmail);
        ensureValidPassword(password);

        UserCredentialEntity existingCredential = userCredentialMapper.findByEmail(normalizedEmail);
        if (existingCredential != null) {
            throw new IllegalStateException("email_already_registered");
        }

        consumeVerificationCode(normalizedEmail, code);

        String nickname = buildNickname(normalizedEmail);
        userMapper.insertWebUser(normalizedEmail, nickname, "");
        UserEntity user = userMapper.findByOpenid(normalizedEmail);
        if (user == null || user.getId() == null) {
            throw new IllegalStateException("created_user_not_found");
        }

        String passwordHash = hashPassword(password);
        userCredentialMapper.insertCredential(user.getId(), normalizedEmail, passwordHash);
        return buildLoginResponse(user.getId(), nickname, "");
    }

    public LoginResponse login(String email, String password) {
        String normalizedEmail = normalizeEmail(email);
        ensureValidEmail(normalizedEmail);
        ensureValidPassword(password);

        UserCredentialEntity credential = userCredentialMapper.findByEmail(normalizedEmail);
        if (credential == null) {
            throw new IllegalStateException("email_not_registered");
        }

        if (!verifyPassword(password, credential.getPasswordHash())) {
            throw new IllegalStateException("invalid_password");
        }

        return buildLoginResponse(credential.getUserId(), buildNickname(normalizedEmail), "");
    }

    public void resetPassword(String email, String password, String code) {
        String normalizedEmail = normalizeEmail(email);
        ensureValidEmail(normalizedEmail);
        ensureValidPassword(password);

        UserCredentialEntity credential = userCredentialMapper.findByEmail(normalizedEmail);
        if (credential == null) {
            throw new IllegalStateException("email_not_registered");
        }

        consumeVerificationCode(normalizedEmail, code);
        String passwordHash = hashPassword(password);
        userCredentialMapper.updatePassword(credential.getUserId(), passwordHash);
    }

    private LoginResponse buildLoginResponse(Long userId, String nickname, String avatar) {
        String token = authTokenService.createAccessToken(userId);
        String refreshToken = authTokenService.createRefreshToken(userId);
        return new LoginResponse(
                token,
                refreshToken,
                authTokenService.getAccessExpireSeconds(),
                new UserProfileResponse(userId, nickname, avatar)
        );
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeScene(String scene) {
        return scene == null ? "" : scene.trim().toLowerCase(Locale.ROOT);
    }

    private void ensureValidEmail(String email) {
        if (email.isBlank() || !email.endsWith("@qq.com")) {
            throw new IllegalStateException("email_not_supported");
        }
    }

    private void ensureValidPassword(String password) {
        if (password == null || password.length() < 6) {
            throw new IllegalStateException("password_too_short");
        }
    }

    private void consumeVerificationCode(String email, String code) {
        EmailVerificationEntity record = emailVerificationMapper.findLatestValid(email);
        if (record == null || !verifyVerificationCode(code, record.getCodeHash())) {
            throw new IllegalStateException("code_invalid");
        }
        emailVerificationMapper.markUsed(record.getId());
    }

    private String buildNickname(String email) {
        int index = email.indexOf('@');
        String nickname = index > 0 ? email.substring(0, index) : email;
        return nickname.isBlank() ? "QQ鐢ㄦ埛" : nickname;
    }

    private String generateCode() {
        int value = new SecureRandom().nextInt(900000) + 100000;
        return String.valueOf(value);
    }

    private String hashVerificationCode(String code) {
        byte[] hash = sha256(code.getBytes(StandardCharsets.UTF_8));
        return toHex(hash);
    }

    private boolean verifyVerificationCode(String code, String hash) {
        if (code == null || hash == null || hash.isBlank()) {
            return false;
        }
        byte[] actual = sha256(code.getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(actual, fromHex(hash));
    }

    private String hashPassword(String password) {
        byte[] salt = new byte[SALT_LENGTH];
        new SecureRandom().nextBytes(salt);
        byte[] hash = sha256(concat(salt, password.getBytes(StandardCharsets.UTF_8)));
        return toHex(salt) + ":" + toHex(hash);
    }

    private boolean verifyPassword(String password, String storedHash) {
        if (storedHash == null || !storedHash.contains(":")) {
            return false;
        }
        String[] parts = storedHash.split(":", 2);
        byte[] salt = fromHex(parts[0]);
        byte[] expected = fromHex(parts[1]);
        byte[] actual = sha256(concat(salt, password.getBytes(StandardCharsets.UTF_8)));
        return MessageDigest.isEqual(expected, actual);
    }

    private byte[] sha256(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(input);
        } catch (Exception error) {
            throw new IllegalStateException("hash_failed", error);
        }
    }

    private byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private String toHex(byte[] data) {
        StringBuilder builder = new StringBuilder(data.length * 2);
        for (byte value : data) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private byte[] fromHex(String hex) {
        int length = hex.length();
        byte[] data = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
