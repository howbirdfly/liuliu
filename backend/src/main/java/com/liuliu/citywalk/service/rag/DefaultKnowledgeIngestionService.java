package com.liuliu.citywalk.service.rag;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DefaultKnowledgeIngestionService implements KnowledgeIngestionService {

    private final VectorStore vectorStore;

    public DefaultKnowledgeIngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void upsert(List<KnowledgeDocument> documents) {
        vectorStore.upsertDocuments(documents);
    }

    @Override
    public void removeBySource(String sourceType, String sourceId) {
        vectorStore.deleteBySource(sourceType, sourceId);
    }
}
