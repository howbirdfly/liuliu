package com.liuliu.citywalk.service.rag;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DefaultKnowledgeRetrievalService implements KnowledgeRetrievalService {

    private final VectorStore vectorStore;

    public DefaultKnowledgeRetrievalService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public List<KnowledgeHit> retrieve(VectorSearchQuery query) {
        return vectorStore.search(query);
    }
}
