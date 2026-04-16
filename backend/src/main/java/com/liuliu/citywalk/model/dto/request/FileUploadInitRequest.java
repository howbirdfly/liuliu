package com.liuliu.citywalk.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record FileUploadInitRequest(
        @NotBlank(message = "bizType_required") String bizType,
        @NotBlank(message = "file_name_required") String fileName,
        String contentType,
        @NotNull(message = "file_size_required") Long size
) {
}
