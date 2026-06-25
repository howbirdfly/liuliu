package com.liuliu.citywalk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.service.agent.LlmMessage;
import com.liuliu.citywalk.service.agent.LlmRequest;
import com.liuliu.citywalk.service.agent.LlmToolCall;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AgentContextWindowService {

    private static final int NORMAL_RECENT_MESSAGE_COUNT = 6;
    private static final int OVERFLOW_RECENT_MESSAGE_COUNT = 4;
    private static final int SUMMARY_NOTE_LIMIT = 3;
    private static final int NORMAL_MESSAGE_CHAR_LIMIT = 900;
    private static final int OVERFLOW_MESSAGE_CHAR_LIMIT = 320;
    private static final int NORMAL_TOOL_OUTPUT_CHAR_LIMIT = 1200;
    private static final int OVERFLOW_TOOL_OUTPUT_CHAR_LIMIT = 480;
    private static final int SUMMARY_CHAR_LIMIT = 900;

    private final ObjectMapper objectMapper;

    public AgentContextWindowService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<LlmMessage> buildConversationMessages(List<LlmMessage> history, String userPrompt) {
        return buildConversationMessages(history, userPrompt, "", "");
    }

    public List<LlmMessage> buildConversationMessages(
            List<LlmMessage> history,
            String userPrompt,
            String carryoverContext
    ) {
        return buildConversationMessages(history, userPrompt, carryoverContext, "");
    }

    public List<LlmMessage> buildConversationMessages(
            List<LlmMessage> history,
            String userPrompt,
            String carryoverContext,
            String stateMessage
    ) {
        List<LlmMessage> normalizedHistory = sanitizeHistory(history, false);
        List<LlmMessage> result = new ArrayList<>();

        int recentStart = Math.max(0, normalizedHistory.size() - NORMAL_RECENT_MESSAGE_COUNT);
        List<LlmMessage> olderMessages = normalizedHistory.subList(0, recentStart);
        String summary = summarizeMessages(olderMessages);
        if (!summary.isBlank()) {
            result.add(LlmMessage.system(summary));
        }
        String normalizedStateMessage = trimText(stateMessage, NORMAL_MESSAGE_CHAR_LIMIT);
        if (!normalizedStateMessage.isBlank()) {
            result.add(LlmMessage.system(normalizedStateMessage));
        }
        String normalizedCarryoverContext = trimText(carryoverContext, NORMAL_MESSAGE_CHAR_LIMIT);
        if (!normalizedCarryoverContext.isBlank()) {
            result.add(LlmMessage.system(normalizedCarryoverContext));
        }
        result.addAll(normalizedHistory.subList(recentStart, normalizedHistory.size()));

        String normalizedPrompt = trimText(userPrompt, NORMAL_MESSAGE_CHAR_LIMIT);
        if (!normalizedPrompt.isBlank()) {
            result.add(LlmMessage.user(normalizedPrompt));
        }
        return result;
    }

    public LlmRequest compactForContextOverflow(LlmRequest request) {
        if (request == null) {
            return null;
        }
        return new LlmRequest(
                request.instructions(),
                buildOverflowMessageWindow(request.messages()),
                request.tools(),
                request.temperature()
        );
    }

    public String compactToolOutputForModel(String output) {
        return compactToolOutput(output, false);
    }

    private List<LlmMessage> buildOverflowMessageWindow(List<LlmMessage> messages) {
        List<LlmMessage> source = sanitizeHistory(messages, true);
        if (source.isEmpty()) {
            return List.of();
        }

        int lastUserIndex = findLastUserIndex(source);
        int recentStart = Math.max(0, source.size() - OVERFLOW_RECENT_MESSAGE_COUNT);
        LinkedHashSet<Integer> keptIndexes = new LinkedHashSet<>();
        if (lastUserIndex >= 0 && lastUserIndex < recentStart) {
            keptIndexes.add(lastUserIndex);
        }
        for (int index = recentStart; index < source.size(); index++) {
            keptIndexes.add(index);
        }

        List<LlmMessage> olderMessages = new ArrayList<>();
        List<LlmMessage> keptMessages = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            LlmMessage message = source.get(index);
            if (keptIndexes.contains(index)) {
                keptMessages.add(message);
            } else {
                olderMessages.add(message);
            }
        }

        List<LlmMessage> result = new ArrayList<>();
        String summary = summarizeMessages(olderMessages);
        if (!summary.isBlank()) {
            result.add(LlmMessage.system(summary));
        }
        result.addAll(keptMessages);
        return result;
    }

    private List<LlmMessage> sanitizeHistory(List<LlmMessage> messages, boolean overflowMode) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<LlmMessage> result = new ArrayList<>();
        for (LlmMessage message : messages) {
            if (message == null || message.role() == null || message.role().isBlank()) {
                continue;
            }
            if ("system".equals(message.role())) {
                continue;
            }
            LlmMessage compacted = compactMessage(message, overflowMode);
            if (compacted != null) {
                result.add(compacted);
            }
        }
        return result;
    }

    private LlmMessage compactMessage(LlmMessage message, boolean overflowMode) {
        int messageLimit = overflowMode ? OVERFLOW_MESSAGE_CHAR_LIMIT : NORMAL_MESSAGE_CHAR_LIMIT;
        String role = message.role();
        if ("tool".equals(role)) {
            String compacted = compactToolOutput(message.content(), overflowMode);
            if (compacted.isBlank()) {
                return null;
            }
            return new LlmMessage(role, compacted, message.toolCallId(), message.name(), List.of());
        }

        String compactedContent = trimText(message.content(), messageLimit);
        List<LlmToolCall> toolCalls = message.toolCalls() == null ? List.of() : message.toolCalls();
        if (compactedContent.isBlank() && toolCalls.isEmpty()) {
            return null;
        }
        return new LlmMessage(role, compactedContent, message.toolCallId(), message.name(), toolCalls);
    }

    private String summarizeMessages(List<LlmMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        ArrayDeque<String> userNotes = new ArrayDeque<>();
        ArrayDeque<String> assistantNotes = new ArrayDeque<>();
        ArrayDeque<String> toolNotes = new ArrayDeque<>();
        Set<String> toolNames = new LinkedHashSet<>();

        for (LlmMessage message : messages) {
            if (message == null || message.role() == null || message.role().isBlank()) {
                continue;
            }
            switch (message.role()) {
                case "user" -> rememberNote(userNotes, previewText(message.content(), 140));
                case "assistant" -> {
                    rememberNote(assistantNotes, previewText(message.content(), 140));
                    if (message.toolCalls() != null) {
                        for (LlmToolCall toolCall : message.toolCalls()) {
                            if (toolCall != null && toolCall.name() != null && !toolCall.name().isBlank()) {
                                toolNames.add(toolCall.name().trim());
                            }
                        }
                    }
                }
                case "tool" -> {
                    if (message.name() != null && !message.name().isBlank()) {
                        toolNames.add(message.name().trim());
                    }
                    String compacted = compactToolOutput(message.content(), true);
                    if (!compacted.isBlank()) {
                        rememberNote(toolNotes, previewText(compacted, 160));
                    }
                }
                default -> {
                }
            }
        }

        List<String> lines = new ArrayList<>();
        if (!userNotes.isEmpty()) {
            lines.add("Earlier user requests: " + String.join(" | ", userNotes));
        }
        if (!assistantNotes.isEmpty()) {
            lines.add("Earlier decisions or suggestions: " + String.join(" | ", assistantNotes));
        }
        if (!toolNames.isEmpty()) {
            lines.add("Tools already used: " + String.join(", ", toolNames));
        }
        if (!toolNotes.isEmpty()) {
            lines.add("Condensed tool results: " + String.join(" | ", toolNotes));
        }
        if (lines.isEmpty()) {
            return "";
        }

        lines.add(0, "Conversation summary from earlier turns:");
        return trimText(String.join("\n", lines), SUMMARY_CHAR_LIMIT);
    }

    private void rememberNote(ArrayDeque<String> notes, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        notes.addLast(value);
        while (notes.size() > SUMMARY_NOTE_LIMIT) {
            notes.removeFirst();
        }
    }

    private int findLastUserIndex(List<LlmMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            LlmMessage message = messages.get(index);
            if (message != null && "user".equals(message.role())) {
                return index;
            }
        }
        return -1;
    }

    private String compactToolOutput(String output, boolean overflowMode) {
        int limit = overflowMode ? OVERFLOW_TOOL_OUTPUT_CHAR_LIMIT : NORMAL_TOOL_OUTPUT_CHAR_LIMIT;
        String normalized = normalizeText(output);
        if (normalized.isBlank()) {
            return "";
        }

        try {
            Object payload = objectMapper.readValue(normalized, Object.class);
            String structuredSummary = summarizeStructuredPayload(payload);
            if (!structuredSummary.isBlank()) {
                return trimText(structuredSummary, limit);
            }
        } catch (Exception ignored) {
            // fall through to plain-text compaction
        }
        return trimText(normalized, limit);
    }

    private String summarizeStructuredPayload(Object payload) {
        if (payload instanceof Map<?, ?> map) {
            List<String> parts = new ArrayList<>();
            appendSimpleField(map, parts, "success");
            appendSimpleField(map, parts, "code");
            appendSimpleField(map, parts, "message");
            appendSimpleField(map, parts, "error");
            appendSimpleField(map, parts, "fallbackSuggestion");
            appendSimpleField(map, parts, "city");
            appendSimpleField(map, parts, "area");
            appendSimpleField(map, parts, "title");
            appendSimpleField(map, parts, "summary");
            appendCollectionField(map, parts, "results");
            appendCollectionField(map, parts, "items");
            appendCollectionField(map, parts, "pois");
            appendCollectionField(map, parts, "routes");
            return String.join("; ", parts);
        }
        if (payload instanceof Iterable<?> iterable) {
            List<String> items = new ArrayList<>();
            for (Object item : iterable) {
                items.add(previewText(summarizeScalar(item), 120));
                if (items.size() >= 3) {
                    break;
                }
            }
            return items.isEmpty() ? "" : "items=" + String.join(" | ", items);
        }
        return summarizeScalar(payload);
    }

    private void appendSimpleField(Map<?, ?> map, List<String> parts, String key) {
        Object value = map.get(key);
        if (value == null) {
            return;
        }
        String summary = summarizeScalar(value);
        if (!summary.isBlank()) {
            parts.add(key + "=" + previewText(summary, 120));
        }
    }

    private void appendCollectionField(Map<?, ?> map, List<String> parts, String key) {
        Object value = map.get(key);
        if (!(value instanceof Iterable<?> iterable)) {
            return;
        }
        List<String> items = new ArrayList<>();
        for (Object item : iterable) {
            items.add(previewText(summarizeScalar(item), 100));
            if (items.size() >= 2) {
                break;
            }
        }
        if (!items.isEmpty()) {
            parts.add(key + "=" + String.join(" | ", items));
        }
    }

    private String summarizeScalar(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> nestedMap) {
            Object title = firstPresent(nestedMap, "title", "name", "summary", "area", "city", "message");
            return title == null ? normalizeText(String.valueOf(nestedMap)) : normalizeText(String.valueOf(title));
        }
        return normalizeText(String.valueOf(value));
    }

    private Object firstPresent(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key) && map.get(key) != null) {
                return map.get(key);
            }
        }
        return null;
    }

    private String previewText(String value, int maxLength) {
        String normalized = normalizeText(value);
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private String trimText(String value, int maxLength) {
        String normalized = normalizeText(value);
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }
}
