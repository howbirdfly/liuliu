package com.liuliu.citywalk.service.rag;

import com.liuliu.citywalk.config.RagProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识文本分片器。
 *
 * <p>切分策略是“结构优先 + 分隔符递归 + 贪心合并”：
 * 先按空行把文本切成段落（本项目的知识文本就是按 section 用 {@code "\n\n"} 拼接的），
 * 段落超过 chunkSize 时再按“换行 → 句末标点 → 分句标点 → 空格 → 单字”的优先级递归切分，
 * 最后把碎片贪心合并到 chunkSize 以内，并在相邻分片之间保留 overlap 字符的重叠。
 */
@Component
public class KnowledgeTextChunker {

    /** 段落分隔符，同时也是最高优先级的分片边界。 */
    private static final String PARAGRAPH_PATTERN = "\\n\\s*\\n";

    /** 递归切分的分隔符优先级，空字符串表示按单字硬切兜底。 */
    private static final List<String> SEPARATORS = List.of(
            "\n", "。", "！", "？", "；", "，", "、", " ", ""
    );

    /** 计算重叠片段时优先选择的句子边界字符。 */
    private static final String BOUNDARY_CHARS = "。！？；，、\n";

    private final int chunkSize;
    private final int overlap;

    @Autowired
    public KnowledgeTextChunker(RagProperties ragProperties) {
        this(ragProperties.getChunkSize(), ragProperties.getChunkOverlap());
    }

    public KnowledgeTextChunker(int chunkSize, int overlap) {
        this.chunkSize = Math.max(1, chunkSize);
        this.overlap = Math.max(0, Math.min(overlap, this.chunkSize / 2));
    }

    public int chunkSize() {
        return chunkSize;
    }

    public int overlap() {
        return overlap;
    }

    public List<String> split(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return List.of();
        }
        if (normalized.length() <= chunkSize) {
            return List.of(normalized);
        }

        List<Piece> pieces = new ArrayList<>();
        for (String block : normalized.split(PARAGRAPH_PATTERN)) {
            String normalizedBlock = normalize(block);
            if (normalizedBlock.isBlank()) {
                continue;
            }
            if (normalizedBlock.length() <= chunkSize) {
                pieces.add(new Piece(normalizedBlock, true));
                continue;
            }
            List<String> splitBlock = splitRecursively(normalizedBlock, 0);
            for (int index = 0; index < splitBlock.size(); index++) {
                pieces.add(new Piece(splitBlock.get(index), index == 0));
            }
        }
        return pack(pieces);
    }

    private List<String> splitRecursively(String text, int separatorIndex) {
        if (text.length() <= chunkSize) {
            return List.of(text);
        }
        for (int index = separatorIndex; index < SEPARATORS.size(); index++) {
            String separator = SEPARATORS.get(index);
            if (separator.isEmpty()) {
                break;
            }
            if (!text.contains(separator)) {
                continue;
            }
            List<String> result = new ArrayList<>();
            for (String part : splitKeepingSeparator(text, separator)) {
                String normalizedPart = normalize(part);
                if (normalizedPart.isBlank()) {
                    continue;
                }
                if (normalizedPart.length() <= chunkSize) {
                    result.add(normalizedPart);
                } else {
                    result.addAll(splitRecursively(normalizedPart, index + 1));
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        }
        return hardSplit(text);
    }

    private List<String> splitKeepingSeparator(String text, String separator) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int found = text.indexOf(separator, start);
        while (found >= 0) {
            parts.add(text.substring(start, found + separator.length()));
            start = found + separator.length();
            found = text.indexOf(separator, start);
        }
        if (start < text.length()) {
            parts.add(text.substring(start));
        }
        return parts;
    }

    private List<String> hardSplit(String text) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + chunkSize);
            String part = normalize(text.substring(start, end));
            if (!part.isBlank()) {
                parts.add(part);
            }
            if (end >= text.length()) {
                break;
            }
            start = end;
        }
        return parts;
    }

    private List<String> pack(List<Piece> pieces) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (Piece piece : pieces) {
            String value = normalize(piece.text());
            if (value.isBlank()) {
                continue;
            }
            String glue = current.length() == 0 ? "" : (piece.sectionStart() ? "\n" : "");
            if (current.length() > 0 && current.length() + glue.length() + value.length() > chunkSize) {
                String flushed = normalize(current.toString());
                chunks.add(flushed);
                current.setLength(0);
                int maxCarry = Math.max(0, chunkSize - value.length() - 1);
                String carry = overlapTail(flushed);
                if (carry.length() > maxCarry) {
                    carry = normalize(carry.substring(carry.length() - maxCarry));
                }
                if (!carry.isBlank()) {
                    current.append(carry);
                }
                glue = current.length() == 0 ? "" : (piece.sectionStart() ? "\n" : "");
            }
            if (current.length() > 0 && !glue.isEmpty()) {
                current.append(glue);
            }
            current.append(value);
        }
        String tail = normalize(current.toString());
        if (!tail.isBlank()) {
            chunks.add(tail);
        }
        return chunks;
    }

    /**
     * 取上一片尾部作为下一片的开头重叠，尽量从句读边界开始，避免下一片以半句话开头。
     */
    private String overlapTail(String chunk) {
        if (overlap <= 0 || chunk.length() <= overlap) {
            return "";
        }
        String tail = normalize(chunk.substring(chunk.length() - overlap));
        for (int index = 0; index < tail.length(); index++) {
            if (BOUNDARY_CHARS.indexOf(tail.charAt(index)) >= 0) {
                String candidate = normalize(tail.substring(index + 1));
                if (candidate.length() >= overlap / 2) {
                    return candidate;
                }
            }
        }
        return tail;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private record Piece(String text, boolean sectionStart) {
    }
}
