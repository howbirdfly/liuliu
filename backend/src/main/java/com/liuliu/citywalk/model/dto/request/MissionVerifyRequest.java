package com.liuliu.citywalk.model.dto.request;

import java.util.List;

public record MissionVerifyRequest(
        String mission,
        String noteText,
        List<String> fileIDs,
        List<String> fileUrls
) {
}
