package com.liuliu.citywalk.service.rag;

import java.util.List;

public interface KnowledgeIngestionService {

    void upsert(List<KnowledgeDocument> documents);

    void removeBySource(String sourceType, String sourceId);
}
