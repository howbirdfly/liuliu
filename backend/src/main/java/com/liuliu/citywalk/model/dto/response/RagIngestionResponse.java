package com.liuliu.citywalk.model.dto.response;

import java.util.List;

public record RagIngestionResponse(
        int walkCount,
        int chunkCount,
        List<Long> walkIds
) {
}
