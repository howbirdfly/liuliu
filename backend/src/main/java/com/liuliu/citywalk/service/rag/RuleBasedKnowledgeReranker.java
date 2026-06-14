package com.liuliu.citywalk.service.rag;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class RuleBasedKnowledgeReranker {

    private static final double TITLE_TERM_WEIGHT = 0.18D;
    private static final double TAG_TERM_WEIGHT = 0.24D;
    private static final double LOCATION_TERM_WEIGHT = 0.20D;
    private static final double CONTENT_TERM_WEIGHT = 0.12D;
    private static final double FULL_QUERY_TITLE_BONUS = 0.12D;
    private static final double FULL_QUERY_TAG_BONUS = 0.14D;
    private static final double FULL_QUERY_LOCATION_BONUS = 0.10D;
    private static final double RECENT_30_DAY_BONUS = 0.04D;
    private static final double RECENT_90_DAY_BONUS = 0.02D;

    public List<KnowledgeHit> rerank(String queryText, int topK, List<KnowledgeHit> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (topK <= 0) {
            return List.of();
        }

        String normalizedQuery = normalizeText(queryText);
        Set<String> terms = extractTerms(queryText);
        if (normalizedQuery.isBlank() && terms.isEmpty()) {
            return candidates.stream()
                    .sorted(Comparator.comparingDouble(KnowledgeHit::score).reversed())
                    .limit(topK)
                    .toList();
        }

        List<ScoredKnowledgeHit> scoredHits = new ArrayList<>(candidates.size());
        for (KnowledgeHit candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            double rerankBoost = computeBoost(candidate, normalizedQuery, terms);
            double finalScore = candidate.score() + rerankBoost;
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (candidate.metadata() != null && !candidate.metadata().isEmpty()) {
                metadata.putAll(candidate.metadata());
            }
            metadata.put("original_score", candidate.score());
            metadata.put("rerank_boost", rerankBoost);
            metadata.put("rerank_score", finalScore);
            scoredHits.add(new ScoredKnowledgeHit(
                    new KnowledgeHit(
                            candidate.chunkId(),
                            candidate.sourceId(),
                            candidate.sourceType(),
                            candidate.title(),
                            candidate.content(),
                            finalScore,
                            metadata
                    ),
                    finalScore
            ));
        }

        return scoredHits.stream()
                .sorted(Comparator.comparingDouble(ScoredKnowledgeHit::finalScore).reversed())
                .limit(topK)
                .map(ScoredKnowledgeHit::hit)
                .toList();
    }

    private double computeBoost(KnowledgeHit hit, String normalizedQuery, Set<String> terms) {
        String normalizedTitle = normalizeText(hit.title());
        String normalizedContent = normalizeText(hit.content());
        String normalizedLocation = normalizeMetadataText(hit.metadata(), "location_name");
        String normalizedTags = normalizeMetadataText(hit.metadata(), "tags");

        double boost = 0D;
        boost += TITLE_TERM_WEIGHT * overlapRatio(terms, normalizedTitle);
        boost += TAG_TERM_WEIGHT * overlapRatio(terms, normalizedTags);
        boost += LOCATION_TERM_WEIGHT * overlapRatio(terms, normalizedLocation);
        boost += CONTENT_TERM_WEIGHT * overlapRatio(terms, normalizedContent);

        if (!normalizedQuery.isBlank()) {
            if (!normalizedTitle.isBlank() && normalizedTitle.contains(normalizedQuery)) {
                boost += FULL_QUERY_TITLE_BONUS;
            }
            if (!normalizedTags.isBlank() && normalizedTags.contains(normalizedQuery)) {
                boost += FULL_QUERY_TAG_BONUS;
            }
            if (!normalizedLocation.isBlank() && normalizedLocation.contains(normalizedQuery)) {
                boost += FULL_QUERY_LOCATION_BONUS;
            }
        }

        boost += recencyBoost(hit.metadata());
        return boost;
    }

    private double overlapRatio(Set<String> terms, String candidateText) {
        if (terms == null || terms.isEmpty() || candidateText == null || candidateText.isBlank()) {
            return 0D;
        }
        int matched = 0;
        for (String term : terms) {
            if (term != null && !term.isBlank() && candidateText.contains(term)) {
                matched++;
            }
        }
        return matched <= 0 ? 0D : (double) matched / (double) terms.size();
    }

    private double recencyBoost(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return 0D;
        }
        Object createdAt = metadata.get("created_at");
        if (createdAt == null) {
            return 0D;
        }
        try {
            Instant instant = Instant.parse(String.valueOf(createdAt).trim());
            long days = Math.max(0L, Duration.between(instant, Instant.now()).toDays());
            if (days <= 30L) {
                return RECENT_30_DAY_BONUS;
            }
            if (days <= 90L) {
                return RECENT_90_DAY_BONUS;
            }
            return 0D;
        } catch (Exception error) {
            return 0D;
        }
    }

    private String normalizeMetadataText(Map<String, Object> metadata, String key) {
        if (metadata == null || metadata.isEmpty() || key == null || key.isBlank()) {
            return "";
        }
        return normalizeText(metadata.get(key));
    }

    private Set<String> extractTerms(String text) {
        String normalized = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return Set.of();
        }

        Set<String> terms = new LinkedHashSet<>();
        String[] segments = normalized
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}]+", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .split(" ");
        for (String segment : segments) {
            String term = normalizeText(segment);
            if (term.length() >= 2) {
                terms.add(term);
            }
        }

        String full = normalizeText(normalized);
        if (full.length() >= 2) {
            terms.add(full);
        }
        return terms;
    }

    private String normalizeText(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "")
                .trim();
    }

    private record ScoredKnowledgeHit(
            KnowledgeHit hit,
            double finalScore
    ) {
    }
}
