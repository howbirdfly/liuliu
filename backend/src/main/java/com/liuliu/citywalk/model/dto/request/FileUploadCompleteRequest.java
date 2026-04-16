package com.liuliu.citywalk.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record FileUploadCompleteRequest(
        @NotBlank(message = "file_id_required") String fileId,
        @NotBlank(message = "bizType_required") String bizType,
        @NotBlank(message = "file_name_required") String fileName,
        @NotBlank(message = "object_name_required") String objectName,
        @NotBlank(message = "url_required") String url,
        String contentType,
        @NotNull(message = "file_size_required") Long size
) {
}
