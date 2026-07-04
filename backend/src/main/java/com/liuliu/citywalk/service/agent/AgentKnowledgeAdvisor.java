package com.liuliu.citywalk.service.agent;

import com.liuliu.citywalk.service.rag.SpringAiKnowledgeDocumentService;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AgentKnowledgeAdvisor implements BaseAdvisor {

    public static final String RETRIEVAL_QUERY = "agent_retrieval_query";
    public static final String RETRIEVAL_TOP_K = "agent_retrieval_top_k";

    private static final int DEFAULT_TOP_K = 3;
    private static final int MAX_CONTEXT_CHARS = 1600;
    private static final int MAX_DOCUMENT_CHARS = 320;

    private final SpringAiKnowledgeDocumentService springAiKnowledgeDocumentService;

    public AgentKnowledgeAdvisor(SpringAiKnowledgeDocumentService springAiKnowledgeDocumentService) {
        this.springAiKnowledgeDocumentService = springAiKnowledgeDocumentService;
    }

    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE - 100;
    }

    @Override
    public String getName() {
        return "agentKnowledgeAdvisor";
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        if (chatClientRequest == null || chatClientRequest.prompt() == null || !springAiKnowledgeDocumentService.isReady()) {
            return chatClientRequest;
        }

        String query = resolveQuery(chatClientRequest.context());
        if (query.isBlank()) {
            return chatClientRequest;
        }

        int topK = resolveTopK(chatClientRequest.context());
        List<Document> documents = springAiKnowledgeDocumentService.search(query, topK, Map.of());
        String knowledgeContext = buildKnowledgeContext(query, documents);
        if (knowledgeContext.isBlank()) {
            return chatClientRequest;
        }

        Prompt prompt = chatClientRequest.prompt();
        List<Message> messages = new ArrayList<>(prompt.getInstructions().size() + 1);
        messages.addAll(prompt.getInstructions());
        int insertIndex = countLeadingSystemMessages(messages);
        messages.add(insertIndex, new SystemMessage(knowledgeContext));
        return chatClientRequest.mutate()
                .prompt(new Prompt(messages, prompt.getOptions()))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }

    private String buildKnowledgeContext(String query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return "";
        }

        List<String> lines = new ArrayList<>();
        lines.add("Retrieved knowledge context from the City Walk knowledge base:");
        lines.add("- Query: " + query);

        int index = 1;
        for (Document document : documents) {
            if (document == null || !document.isText()) {
                continue;
            }
            String title = readString(document.getMetadata(), "title", "Untitled reference");
            String sourceType = readString(document.getMetadata(), "source_type", "");
            String content = trimText(document.getText(), MAX_DOCUMENT_CHARS);
            if (content.isBlank()) {
                continue;
            }

            StringBuilder line = new StringBuilder();
            line.append(index).append(". ").append(title);
            if (!sourceType.isBlank()) {
                line.append(" [").append(sourceType).append("]");
            }
            line.append(": ").append(content);
            lines.add(line.toString());
            index++;
        }

        if (index == 1) {
            return "";
        }
        lines.add("Use these references as grounded context, but do not copy them verbatim.");
        return trimText(String.join("\n", lines), MAX_CONTEXT_CHARS);
    }

    private int countLeadingSystemMessages(List<Message> messages) {
        int index = 0;
        while (index < messages.size() && messages.get(index) instanceof SystemMessage) {
            index++;
        }
        return index;
    }

    private String resolveQuery(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return "";
        }
        Object value = context.get(RETRIEVAL_QUERY);
        if (value == null) {
            return "";
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() ? "" : normalized;
    }

    private int resolveTopK(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return DEFAULT_TOP_K;
        }
        Object value = context.get(RETRIEVAL_TOP_K);
        if (value instanceof Number number) {
            return Math.max(1, Math.min(number.intValue(), 6));
        }
        if (value instanceof String text) {
            try {
                return Math.max(1, Math.min(Integer.parseInt(text.trim()), 6));
            } catch (NumberFormatException ignored) {
                return DEFAULT_TOP_K;
            }
        }
        return DEFAULT_TOP_K;
    }

    private String readString(Map<String, Object> metadata, String key, String fallback) {
        if (metadata == null || metadata.isEmpty()) {
            return fallback;
        }
        Object value = metadata.get(key);
        if (value == null) {
            return fallback;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() ? fallback : normalized;
    }

    private String trimText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }
}
