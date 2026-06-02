package com.liuliu.citywalk.service.rag;

import java.util.List;

public interface VectorStore {

    String provider();

    boolean isEnabled();

    VectorStoreHealth health();

    void ensureCollection();

    void upsertDocuments(List<KnowledgeDocument> documents);

    List<KnowledgeHit> search(VectorSearchQuery query);

    void deleteBySource(String sourceType, String sourceId);
}
