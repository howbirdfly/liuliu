package com.liuliu.citywalk.service.rag;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SpringAiDocumentMapper {

    public Document toDocument(KnowledgeHit hit) {
        if (hit == null) {
            return null;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (hit.metadata() != null && !hit.metadata().isEmpty()) {
            metadata.putAll(hit.metadata());
        }
        metadata.putIfAbsent("chunk_id", hit.chunkId());
        metadata.putIfAbsent("source_id", hit.sourceId());
        metadata.putIfAbsent("source_type", hit.sourceType());
        metadata.putIfAbsent("title", hit.title());
        metadata.put("score", hit.score());

        return new Document(
                hit.chunkId() == null || hit.chunkId().isBlank() ? hit.sourceId() : hit.chunkId(),
                hit.content() == null ? "" : hit.content(),
                metadata
        );
    }

    public Document toDocument(KnowledgeDocument document) {
        if (document == null) {
            return null;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (document.metadata() != null && !document.metadata().isEmpty()) {
            metadata.putAll(document.metadata());
        }
        metadata.putIfAbsent("chunk_id", document.chunkId());
        metadata.putIfAbsent("source_id", document.sourceId());
        metadata.putIfAbsent("source_type", document.sourceType());
        metadata.putIfAbsent("title", document.title());

        return new Document(
                document.chunkId() == null || document.chunkId().isBlank() ? document.sourceId() : document.chunkId(),
                document.content() == null ? "" : document.content(),
                metadata
        );
    }

    public KnowledgeDocument toKnowledgeDocument(Document document, List<Float> embedding) {
        if (document == null || !document.isText()) {
            return null;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (document.getMetadata() != null && !document.getMetadata().isEmpty()) {
            metadata.putAll(document.getMetadata());
        }

        String chunkId = firstNonBlank(
                stringValue(metadata.get("chunk_id")),
                document.getId(),
                stringValue(metadata.get("source_id"))
        );
        String sourceId = firstNonBlank(
                stringValue(metadata.get("source_id")),
                document.getId(),
                chunkId
        );
        String sourceType = stringValue(metadata.get("source_type"));
        String title = stringValue(metadata.get("title"));

        return new KnowledgeDocument(
                chunkId,
                sourceId,
                sourceType,
                title,
                document.getText() == null ? "" : document.getText(),
                embedding == null ? List.of() : embedding,
                metadata
        );
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        if (value == null) {
            return "";
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() ? "" : normalized;
    }
}
