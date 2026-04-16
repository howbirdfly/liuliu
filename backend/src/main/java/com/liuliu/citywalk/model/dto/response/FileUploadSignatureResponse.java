package com.liuliu.citywalk.model.dto.response;

public record FileUploadSignatureResponse(
        String fileId,
        String objectName,
        String host,
        String policy,
        String signature,
        String accessKeyId,
        String url,
        long expireAt,
        String successActionStatus
) {
}
