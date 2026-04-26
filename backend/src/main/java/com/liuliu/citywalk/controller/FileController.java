package com.liuliu.citywalk.controller;

import com.liuliu.citywalk.common.ApiResponse;
import com.liuliu.citywalk.model.dto.request.FileUploadCompleteRequest;
import com.liuliu.citywalk.model.dto.request.FileUploadInitRequest;
import com.liuliu.citywalk.model.dto.response.FileUploadSignatureResponse;
import com.liuliu.citywalk.model.dto.response.FileUploadResponse;
import com.liuliu.citywalk.config.AliOssProperties;
import com.liuliu.citywalk.mapper.UploadedFileMapper;
import com.liuliu.citywalk.service.UserSessionService;
import com.liuliu.citywalk.util.AliOssUtil;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private final UploadedFileMapper uploadedFileMapper;
    private final UserSessionService userSessionService;
    private final AliOssUtil aliOssUtil;
    private final AliOssProperties aliOssProperties;

    private static final long MAX_UPLOAD_SIZE = 20L * 1024L * 1024L;
    private static final long DIRECT_UPLOAD_EXPIRE_SECONDS = 600L;
    private static final String SUCCESS_ACTION_STATUS = "200";

    public FileController(UploadedFileMapper uploadedFileMapper,
                          UserSessionService userSessionService,
                          AliOssUtil aliOssUtil,
                          AliOssProperties aliOssProperties) {
        this.uploadedFileMapper = uploadedFileMapper;
        this.userSessionService = userSessionService;
        this.aliOssUtil = aliOssUtil;
        this.aliOssProperties = aliOssProperties;
    }

    @PostMapping("/upload/init")
    public ApiResponse<FileUploadSignatureResponse> initUpload(@Valid @RequestBody FileUploadInitRequest request) {
        String safeBizType = normalizeSegment(request.bizType(), "common");
        String originalName = request.fileName();
        String extension = extractExtension(originalName);
        String fileId = "f_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().replace("-", "");
        String fileName = fileId + extension;
        String objectName = safeBizType + "/" + fileName;
        String host = buildHost();
        String url = host + "/" + objectName;
        long expireAt = Instant.now().plusSeconds(DIRECT_UPLOAD_EXPIRE_SECONDS).getEpochSecond();
        String expiration = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(expireAt));
        long sizeLimit = Math.max(request.size(), MAX_UPLOAD_SIZE);
        String policyDocument = """
                {"expiration":"%s","conditions":[["starts-with","$key","%s/"],["content-length-range",0,%d],{"success_action_status":"%s"}]}
                """.formatted(expiration, safeBizType, sizeLimit, SUCCESS_ACTION_STATUS);
        String policy = Base64.getEncoder().encodeToString(policyDocument.getBytes(StandardCharsets.UTF_8));
        String signature = signPolicy(policy, aliOssProperties.getAccessKeySecret());

        return ApiResponse.success(new FileUploadSignatureResponse(
                fileId,
                objectName,
                host,
                policy,
                signature,
                aliOssProperties.getAccessKeyId(),
                url,
                expireAt,
                SUCCESS_ACTION_STATUS
        ));
    }

    @PostMapping("/upload/complete")
    public ApiResponse<FileUploadResponse> completeUpload(
            @Valid @RequestBody FileUploadCompleteRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
        ) {
        String safeBizType = normalizeSegment(request.bizType(), "common");
        UserSessionService.StoredUser user = userSessionService.resolveUser(authorizationHeader);
        uploadedFileMapper.insertFile(
                user == null || user.isGuest() ? null : user.id(),
                safeBizType,
                request.objectName(),
                request.fileName(),
                request.url(),
                request.contentType(),
                request.size()
        );

        return ApiResponse.success(new FileUploadResponse(
                request.fileId(),
                request.url(),
                request.contentType(),
                request.size()
        ));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FileUploadResponse> upload(@RequestPart("file") MultipartFile file,
                                                  @RequestParam("bizType") String bizType,
                                                  @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) throws IOException {
        String safeBizType = normalizeSegment(bizType, "common");
        String originalName = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String extension = extractExtension(originalName);
        String fileId = "f_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().replace("-", "");
        String fileName = fileId + extension;
        String objectName = safeBizType + "/" + fileName;
        String url = aliOssUtil.upload(file.getBytes(), objectName);

        UserSessionService.StoredUser user = userSessionService.resolveUser(authorizationHeader);
        uploadedFileMapper.insertFile(
                user == null || user.isGuest() ? null : user.id(),
                safeBizType,
                objectName,
                originalName,
                url,
                file.getContentType(),
                file.getSize()
        );

        FileUploadResponse response = new FileUploadResponse(
                fileId,
                url,
                file.getContentType(),
                file.getSize()
        );
        return ApiResponse.success(response);
    }

    private String normalizeSegment(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.replaceAll("[^a-zA-Z0-9_-]", "");
        return normalized.isBlank() ? fallback : normalized;
    }

    private String extractExtension(String filename) {
        int index = filename.lastIndexOf('.');
        if (index < 0 || index == filename.length() - 1) {
            return "";
        }
        return filename.substring(index);
    }

    private String buildHost() {
        String endpoint = aliOssProperties.getEndpoint();
        if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
            return endpoint.replaceFirst("^(https?://)", "https://" + aliOssProperties.getBucketName() + ".");
        }
        return "https://" + aliOssProperties.getBucketName() + "." + endpoint;
    }

    private String signPolicy(String policy, String accessKeySecret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(accessKeySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] signData = mac.doFinal(policy.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signData);
        } catch (Exception error) {
            throw new IllegalStateException("oss_policy_sign_failed", error);
        }
    }
}
