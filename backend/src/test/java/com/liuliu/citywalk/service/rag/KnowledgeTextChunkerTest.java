package com.liuliu.citywalk.service.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeTextChunkerTest {

    private static final int CHUNK_SIZE = 200;
    private static final int OVERLAP = 40;

    @Test
    void keepsShortTextAsSingleChunk() {
        KnowledgeTextChunker chunker = new KnowledgeTextChunker(CHUNK_SIZE, OVERLAP);

        List<String> chunks = chunker.split("主题标题：武康路漫步\n\n地点名称：上海武康路");

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("武康路"));
    }

    @Test
    void splitsLongTextWithinChunkSize() {
        KnowledgeTextChunker chunker = new KnowledgeTextChunker(CHUNK_SIZE, OVERLAP);

        List<String> chunks = chunker.split(buildSentences(60));

        assertTrue(chunks.size() > 1);
        for (String chunk : chunks) {
            assertFalse(chunk.isBlank());
            assertTrue(chunk.length() <= CHUNK_SIZE, "chunk too long: " + chunk.length());
        }
    }

    @Test
    void endsChunksOnSentenceBoundaryWhenPossible() {
        KnowledgeTextChunker chunker = new KnowledgeTextChunker(CHUNK_SIZE, OVERLAP);

        List<String> chunks = chunker.split(buildSentences(40));

        assertTrue(chunks.size() > 1);
        for (int index = 0; index < chunks.size() - 1; index++) {
            assertTrue(
                    chunks.get(index).endsWith("。"),
                    "chunk should end on a sentence boundary: " + chunks.get(index)
            );
        }
    }

    @Test
    void keepsOverlapBetweenAdjacentChunks() {
        KnowledgeTextChunker chunker = new KnowledgeTextChunker(CHUNK_SIZE, OVERLAP);

        List<String> chunks = chunker.split(buildSentences(40));

        assertTrue(chunks.size() > 1);
        for (int index = 0; index < chunks.size() - 1; index++) {
            assertTrue(
                    longestOverlap(chunks.get(index), chunks.get(index + 1)) >= OVERLAP / 2,
                    "missing overlap between chunk " + index + " and " + (index + 1)
            );
        }
    }

    @Test
    void hardSplitsWhenTextHasNoSeparator() {
        KnowledgeTextChunker chunker = new KnowledgeTextChunker(CHUNK_SIZE, OVERLAP);

        List<String> chunks = chunker.split("a".repeat(700));

        assertTrue(chunks.size() >= 4);
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= CHUNK_SIZE);
        }
    }

    private String buildSentences(int count) {
        StringBuilder builder = new StringBuilder();
        for (int index = 1; index <= count; index++) {
            builder.append("这是第").append(index).append("句测试文本，内容长度适中。");
        }
        return builder.toString();
    }

    private int longestOverlap(String left, String right) {
        int maxLength = Math.min(left.length(), right.length());
        for (int length = maxLength; length > 0; length--) {
            if (left.regionMatches(left.length() - length, right, 0, length)) {
                return length;
            }
        }
        return 0;
    }
}
