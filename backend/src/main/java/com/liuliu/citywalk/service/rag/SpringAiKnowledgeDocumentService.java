package com.liuliu.citywalk.service.rag;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class SpringAiKnowledgeDocumentService {

    private final KnowledgeSearchService knowledgeSearchService;
    private final KnowledgeIngestionService knowledgeIngestionService;
    private final EmbeddingService embeddingService;
    private final SpringAiDocumentMapper springAiDocumentMapper;
    private final VectorStore vectorStore;

    public SpringAiKnowledgeDocumentService(
            KnowledgeSearchService knowledgeSearchService,
            KnowledgeIngestionService knowledgeIngestionService,
            EmbeddingService embeddingService,
            SpringAiDocumentMapper springAiDocumentMapper,
            VectorStore vectorStore
    ) {
        this.knowledgeSearchService = knowledgeSearchService;
        this.knowledgeIngestionService = knowledgeIngestionService;
        this.embeddingService = embeddingService;
        this.springAiDocumentMapper = springAiDocumentMapper;
        this.vectorStore = vectorStore;
    }

    public List<Document> search(String queryText, int topK, Map<String, Object> filters) {
        return knowledgeSearchService.searchDocuments(queryText, topK, filters);
    }

    public void upsert(List<Document> documents) {
        List<Document> normalizedDocuments = normalize(documents);
        if (normalizedDocuments.isEmpty()) {
            return;
        }

        List<List<Float>> embeddings = embeddingService.embedAll(
                normalizedDocuments.stream()
                        .map(Document::getText)
                        .toList()
        );
        if (embeddings.size() != normalizedDocuments.size()) {
            throw new IllegalStateException("embedding_count_mismatch");
        }

        List<KnowledgeDocument> knowledgeDocuments = new ArrayList<>(normalizedDocuments.size());
        for (int index = 0; index < normalizedDocuments.size(); index++) {
            KnowledgeDocument knowledgeDocument =
                    springAiDocumentMapper.toKnowledgeDocument(normalizedDocuments.get(index), embeddings.get(index));
            if (knowledgeDocument != null) {
                knowledgeDocuments.add(knowledgeDocument);
            }
        }
        if (!knowledgeDocuments.isEmpty()) {
            knowledgeIngestionService.upsert(knowledgeDocuments);
        }
    }

    public void removeBySource(String sourceType, String sourceId) {
        knowledgeIngestionService.removeBySource(sourceType, sourceId);
    }

    public boolean isReady() {
        return embeddingService.isConfigured() && vectorStore.isEnabled();
    }

    private List<Document> normalize(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        List<Document> result = new ArrayList<>(documents.size());
        for (Document document : documents) {
            if (document == null || !document.isText()) {
                continue;
            }
            String text = document.getText() == null ? "" : document.getText().trim();
            if (text.isBlank()) {
                continue;
            }
            result.add(document);
        }
        return result;
    }
}
