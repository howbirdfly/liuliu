package com.liuliu.citywalk.service;

import com.liuliu.citywalk.model.dto.response.LoginResponse;
import com.liuliu.citywalk.model.dto.response.UserProfileResponse;
import com.liuliu.citywalk.repository.EmailVerificationRepository;
import com.liuliu.citywalk.repository.UserCredentialRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

@Service
public class EmailAuthService {

    private static final int SALT_LENGTH = 16;

    private final AuthTokenService authTokenService;
    private final UserCredentialRepository userCredentialRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final EmailSender emailSender;

    public EmailAuthService(
            AuthTokenService authTokenService,
            UserCredentialRepository userCredentialRepository,
            EmailVerificationRepository emailVerificationRepository,
            EmailSender emailSender
    ) {
        this.authTokenService = authTokenService;
        this.userCredentialRepository = userCredentialRepository;
        this.emailVerificationRepository = emailVerificationRepository;
        this.emailSender = emailSender;
    }

    public void sendVerificationCode(String email) {
        String normalizedEmail = normalizeEmail(email);
        ensureValidEmail(normalizedEmail);

        emailVerificationRepository.findLatest(normalizedEmail).ifPresent(record -> {
            if (record.createdAt() != null && record.createdAt().toInstant().isAfter(Instant.now().minusSeconds(60))) {
                throw new IllegalStateException("code_send_too_frequent");
            }
        });

        String code = generateCode();
        String codeHash = hashVerificationCode(code);
        emailVerificationRepository.create(normalizedEmail, codeHash, Instant.now().plus(10, ChronoUnit.MINUTES));
        emailSender.sendVerificationCode(normalizedEmail, code);
    }

    public LoginResponse register(String email, String password, String code) {
        String normalizedEmail = normalizeEmail(email);
        ensureValidEmail(normalizedEmail);
        ensureValidPassword(password);

        userCredentialRepository.findByEmail(normalizedEmail).ifPresent(item -> {
            throw new IllegalStateException("email_already_registered");
        });

        EmailVerificationRepository.EmailVerificationRecord record = emailVerificationRepository.findLatestValid(normalizedEmail)
                .orElseThrow(() -> new IllegalStateException("code_invalid"));
        if (!verifyVerificationCode(code, record.codeHash())) {
            throw new IllegalStateException("code_invalid");
        }
        emailVerificationRepository.markUsed(record.id());

        Long userId = userCredentialRepository.createUser(
                normalizedEmail,
                buildNickname(normalizedEmail),
                ""
        );
        String passwordHash = hashPassword(password);
        userCredentialRepository.createCredential(userId, normalizedEmail, passwordHash);
        return buildLoginResponse(userId, buildNickname(normalizedEmail), "");
    }

    public LoginResponse login(String email, String password) {
        String normalizedEmail = normalizeEmail(email);
        ensureValidEmail(normalizedEmail);
        ensureValidPassword(password);

        UserCredentialRepository.UserCredentialRecord credential = userCredentialRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new IllegalStateException("email_not_registered"));

        if (!verifyPassword(password, credential.passwordHash())) {
            throw new IllegalStateException("invalid_password");
        }

        return buildLoginResponse(credential.userId(), buildNickname(normalizedEmail), "");
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

    private String buildNickname(String email) {
        int index = email.indexOf('@');
        String nickname = index > 0 ? email.substring(0, index) : email;
        return nickname.isBlank() ? "QQ用户" : nickname;
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
