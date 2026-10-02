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

        List<KnowledgeDocument> knowledgeDocuments = embed(normalizedDocuments);
        if (!knowledgeDocuments.isEmpty()) {
            knowledgeIngestionService.upsert(knowledgeDocuments);
        }
    }

    /**
     * 用新分片整体替换某个来源的旧分片。
     *
     * <p>先算 embedding 再删旧数据：这样向量化失败时旧分片仍然可用，不会出现“删了旧的又没写进新的”的空窗。
     * 分片算法、chunkSize 变化后，同一个来源的分片数量会变，只靠 upsert 会残留旧 chunkId，所以这里必须先删。
     */
    public void replaceBySource(String sourceType, String sourceId, List<Document> documents) {
        List<Document> normalizedDocuments = normalize(documents);
        if (normalizedDocuments.isEmpty()) {
            removeBySource(sourceType, sourceId);
            return;
        }

        List<KnowledgeDocument> knowledgeDocuments = embed(normalizedDocuments);
        removeBySource(sourceType, sourceId);
        if (!knowledgeDocuments.isEmpty()) {
            knowledgeIngestionService.upsert(knowledgeDocuments);
        }
    }

    private List<KnowledgeDocument> embed(List<Document> normalizedDocuments) {
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
        return knowledgeDocuments;
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
