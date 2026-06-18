package com.liuliu.citywalk.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "sky.alioss")
@Data
public class AliOssProperties {

    private String endpoint;
    private String accessKeyId;
    private String accessKeySecret;
    private String bucketName;

    public String toSafeSummary() {
        return "AliOssProperties(endpoint=" + safeText(endpoint)
                + ", accessKeyId=" + mask(accessKeyId)
                + ", accessKeySecret=" + mask(accessKeySecret)
                + ", bucketName=" + safeText(bucketName)
                + ")";
    }

    @Override
    public String toString() {
        return toSafeSummary();
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? "<empty>" : value.trim();
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) {
            return "<empty>";
        }
        String normalized = value.trim();
        if (normalized.length() <= 8) {
            return "***";
        }
        return normalized.substring(0, 4) + "****" + normalized.substring(normalized.length() - 4);
    }
}
