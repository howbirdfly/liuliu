package com.liuliu.citywalk.service.rag;

import java.util.List;

public record CommunityKnowledgeIngestionResult(
        int walkCount,
        int chunkCount,
        List<Long> walkIds
) {
}
