package com.liuliu.citywalk.service.agent;

import com.liuliu.citywalk.service.AgentContextWindowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Service
public class SpringAiLlmClient {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmClient.class);
    private static final String DEFAULT_PROVIDER = "deepseek";

    private final ChatClient chatClient;
    private final AgentChatMemoryAdvisor agentChatMemoryAdvisor;
    private final AgentKnowledgeAdvisor agentKnowledgeAdvisor;
    private final AgentContextWindowService agentContextWindowService;
    private final String configuredProvider;
    private final String configuredModel;
    private final String configuredApiKey;
    private final String configuredBaseUrl;

    public SpringAiLlmClient(
            @Qualifier("deepSeekChatClient") ChatClient chatClient,
            AgentChatMemoryAdvisor agentChatMemoryAdvisor,
            AgentKnowledgeAdvisor agentKnowledgeAdvisor,
            AgentContextWindowService agentContextWindowService,
            @Value("${spring.ai.model.chat:deepseek}") String configuredProvider,
            @Value("${spring.ai.deepseek.chat.model:deepseek-chat}") String configuredModel,
            @Value("${spring.ai.deepseek.api-key:}") String configuredApiKey,
            @Value("${spring.ai.deepseek.base-url:https://api.deepseek.com}") String configuredBaseUrl
    ) {
        this.chatClient = chatClient;
        this.agentChatMemoryAdvisor = agentChatMemoryAdvisor;
        this.agentKnowledgeAdvisor = agentKnowledgeAdvisor;
        this.agentContextWindowService = agentContextWindowService;
        this.configuredProvider = configuredProvider == null || configuredProvider.isBlank()
                ? DEFAULT_PROVIDER
                : configuredProvider.trim();
        this.configuredModel = configuredModel == null || configuredModel.isBlank()
                ? "deepseek-chat"
                : configuredModel.trim();
        this.configuredApiKey = configuredApiKey == null ? "" : configuredApiKey.trim();
        this.configuredBaseUrl = configuredBaseUrl == null ? "" : configuredBaseUrl.trim();
    }

    public String provider() {
        return configuredProvider;
    }

    public String model() {
        return configuredModel;
    }

    public LlmResponse createResponse(
            String instructions,
            List<LlmMessage> messages,
            List<ToolCallback> toolCallbacks,
            Double temperature
    ) {
        return createResponse(instructions, messages, toolCallbacks, temperature, null);
    }

    public LlmResponse createResponse(
            String instructions,
            List<LlmMessage> messages,
            List<ToolCallback> toolCallbacks,
            Double temperature,
            AgentCallContext callContext
    ) {
        ChatClient chatClient = requireChatClient();

        try {
            return callSingleResponse(chatClient, instructions, messages, toolCallbacks, temperature, callContext);
        } catch (Exception error) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw cancelled(error);
            }
            LlmResponse compactedRetry = tryContextOverflowRetry(
                    chatClient,
                    instructions,
                    messages,
                    toolCallbacks,
                    temperature,
                    callContext,
                    null,
                    error
            );
            if (compactedRetry != null) {
                return compactedRetry;
            }
            log.warn(
                    "Spring AI agent call failed. requestSummary=" + summarizeRequest(messages, toolCallbacks)
                            + ", causeType=" + error.getClass().getName()
                            + ", causeMessage=" + error.getMessage(),
                    error
            );
            throw propagate(error);
        }
    }

    public LlmResponse createStreamingResponse(
            String instructions,
            List<LlmMessage> messages,
            List<ToolCallback> toolCallbacks,
            Double temperature,
            Consumer<String> listener
    ) {
        return createStreamingResponse(instructions, messages, toolCallbacks, temperature, null, listener);
    }

    public LlmResponse createStreamingResponse(
            String instructions,
            List<LlmMessage> messages,
            List<ToolCallback> toolCallbacks,
            Double temperature,
            AgentCallContext callContext,
            Consumer<String> listener
    ) {
        ChatClient chatClient = requireChatClient();

        try {
            return callStreamingResponse(chatClient, instructions, messages, toolCallbacks, temperature, callContext, listener);
        } catch (Exception error) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw cancelled(error);
            }
            LlmResponse compactedRetry = tryContextOverflowRetry(
                    chatClient,
                    instructions,
                    messages,
                    toolCallbacks,
                    temperature,
                    callContext,
                    listener,
                    error
            );
            if (compactedRetry != null) {
                return compactedRetry;
            }
            log.warn(
                    "Spring AI agent streaming call failed. requestSummary=" + summarizeRequest(messages, toolCallbacks)
                            + ", causeType=" + error.getClass().getName()
                            + ", causeMessage=" + error.getMessage(),
                    error
            );
            throw propagate(error);
        }
    }

    private LlmResponse tryContextOverflowRetry(
            ChatClient chatClient,
            String instructions,
            List<LlmMessage> messages,
            List<ToolCallback> toolCallbacks,
            Double temperature,
            AgentCallContext callContext,
            Consumer<String> listener,
            Exception error
    ) {
        if (!isContextOverflowError(error)) {
            return null;
        }

        List<LlmMessage> compactedMessages = agentContextWindowService.compactMessagesForContextOverflow(messages);
        if (compactedMessages == null || compactedMessages.isEmpty()) {
            return null;
        }

        log.info(
                "Spring AI request exceeded context window. Retrying with compacted context. originalSummary={}, compactedMessages={}",
                summarizeRequest(messages, toolCallbacks),
                compactedMessages.size()
        );
        try {
            if (listener != null) {
                return callStreamingResponse(chatClient, instructions, compactedMessages, toolCallbacks, temperature, callContext, listener);
            }
            return callSingleResponse(chatClient, instructions, compactedMessages, toolCallbacks, temperature, callContext);
        } catch (Exception retryError) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw cancelled(retryError);
            }
            log.warn(
                    "Spring AI compacted-context retry failed. requestSummary=" + summarizeRequest(compactedMessages, toolCallbacks)
                            + ", causeType=" + retryError.getClass().getName()
                            + ", causeMessage=" + retryError.getMessage(),
                    retryError
            );
            return null;
        }
    }

    private boolean isContextOverflowError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase();
                if (normalized.contains("context length")
                        || normalized.contains("context window")
                        || normalized.contains("maximum context")
                        || normalized.contains("maximum prompt")
                        || normalized.contains("too many tokens")
                        || normalized.contains("input is too long")
                        || normalized.contains("prompt is too long")
                        || normalized.contains("reduce the length")
                        || normalized.contains("context limit")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private LlmResponse callSingleResponse(
            ChatClient chatClient,
            String instructions,
            List<LlmMessage> messages,
            List<ToolCallback> toolCallbacks,
            Double temperature,
            AgentCallContext callContext
    ) {
        ChatResponse response = prepareRequest(chatClient, toPrompt(instructions, messages), toolCallbacks, temperature, callContext)
                .call()
                .chatResponse();
        return toLlmResponse(response);
    }

    private LlmResponse callStreamingResponse(
            ChatClient chatClient,
            String instructions,
            List<LlmMessage> messages,
            List<ToolCallback> toolCallbacks,
            Double temperature,
            AgentCallContext callContext,
            Consumer<String> listener
    ) {
        AtomicReference<ChatResponse> aggregatedResponse = new AtomicReference<>();
        new MessageAggregator()
                .aggregate(
                        prepareRequest(chatClient, toPrompt(instructions, messages), toolCallbacks, temperature, callContext)
                                .stream()
                                .chatResponse(),
                        aggregatedResponse::set
                )
                .doOnNext(chunk -> emitStreamingDelta(chunk, listener))
                .blockLast();

        ChatResponse response = aggregatedResponse.get();
        if (response == null) {
            throw new IllegalStateException("spring_ai_stream_empty");
        }
        return toLlmResponse(response);
    }

    private boolean isConfigured() {
        return !configuredApiKey.isBlank() && !configuredBaseUrl.isBlank();
    }

    private ChatClient resolveChatClient() {
        return chatClient;
    }

    private ChatClient requireChatClient() {
        if (!isConfigured()) {
            throw new IllegalStateException("spring_ai_chat_not_configured");
        }
        ChatClient chatClient = resolveChatClient();
        if (chatClient == null) {
            throw new IllegalStateException("spring_ai_chat_client_unavailable");
        }
        return chatClient;
    }

    private Prompt toPrompt(String instructions, List<LlmMessage> messages) {
        return new Prompt(buildMessages(instructions, messages));
    }

    private ChatClient.ChatClientRequestSpec prepareRequest(
            ChatClient chatClient,
            Prompt prompt,
            List<ToolCallback> toolCallbacks,
            Double temperature,
            AgentCallContext callContext
    ) {
        ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt(prompt)
                .options(buildOptions(temperature));
        List<ToolCallback> normalizedToolCallbacks = toolCallbacks == null ? List.of() : toolCallbacks;
        if (!normalizedToolCallbacks.isEmpty()) {
            requestSpec = requestSpec.toolCallbacks(normalizedToolCallbacks);
        }
        if (callContext != null && callContext.hasConversationId()) {
            requestSpec = requestSpec.advisors(spec -> spec
                    .advisors(agentChatMemoryAdvisor)
                    .param(ChatMemory.CONVERSATION_ID, callContext.conversationId()));
        }
        if (callContext != null && callContext.hasKnowledgeQuery()) {
            requestSpec = requestSpec.advisors(spec -> spec
                    .advisors(agentKnowledgeAdvisor)
                    .param(AgentKnowledgeAdvisor.RETRIEVAL_QUERY, callContext.knowledgeQuery())
                    .param(AgentKnowledgeAdvisor.RETRIEVAL_TOP_K, callContext.knowledgeTopK()));
        }
        return requestSpec;
    }

    private ToolCallingChatOptions buildOptions(Double temperature) {
        return ToolCallingChatOptions.builder()
                .model(model())
                .internalToolExecutionEnabled(false)
                .temperature(temperature == null ? 0.2 : temperature)
                .build();
    }

    private List<Message> buildMessages(String instructions, List<LlmMessage> requestMessages) {
        List<Message> messages = new ArrayList<>();
        if (instructions != null && !instructions.isBlank()) {
            messages.add(new SystemMessage(instructions.trim()));
        }
        if (requestMessages == null || requestMessages.isEmpty()) {
            return messages;
        }

        for (LlmMessage item : requestMessages) {
            Message message = toSpringAiMessage(item);
            if (message != null) {
                messages.add(message);
            }
        }
        return messages;
    }

    private Message toSpringAiMessage(LlmMessage item) {
        if (item == null || item.role() == null || item.role().isBlank()) {
            return null;
        }

        return switch (item.role()) {
            case "system" -> new SystemMessage(item.content() == null ? "" : item.content());
            case "user" -> new UserMessage(item.content() == null ? "" : item.content());
            case "assistant" -> AssistantMessage.builder()
                    .content(item.content())
                    .toolCalls(normalizeToolCalls(item.toolCalls()))
                    .build();
            case "tool" -> ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            item.toolCallId(),
                            item.name(),
                            item.content() == null ? "" : item.content()
                    )))
                    .build();
            default -> null;
        };
    }

    private List<AssistantMessage.ToolCall> normalizeToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }

        List<AssistantMessage.ToolCall> result = new ArrayList<>(toolCalls.size());
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            if (toolCall == null || toolCall.name() == null || toolCall.name().isBlank()) {
                continue;
            }
            result.add(new AssistantMessage.ToolCall(
                    toolCall.id(),
                    toolCall.type() == null || toolCall.type().isBlank() ? "function" : toolCall.type(),
                    toolCall.name(),
                    toolCall.arguments() == null ? "{}" : toolCall.arguments()
            ));
        }
        return result;
    }

    private void emitStreamingDelta(ChatResponse chunk, Consumer<String> listener) {
        if (listener == null || chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
            return;
        }

        String delta = chunk.getResult().getOutput().getText();
        if (delta != null && !delta.isEmpty()) {
            listener.accept(delta);
        }
    }

    private LlmResponse toLlmResponse(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return new LlmResponse("", List.of());
        }

        AssistantMessage output = response.getResult().getOutput();
        return new LlmResponse(
                output.getText() == null ? "" : output.getText(),
                extractToolCalls(output)
        );
    }

    private List<AssistantMessage.ToolCall> extractToolCalls(AssistantMessage message) {
        if (message == null || !message.hasToolCalls()) {
            return List.of();
        }

        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        for (AssistantMessage.ToolCall toolCall : message.getToolCalls()) {
            if (toolCall == null || toolCall.name() == null || toolCall.name().isBlank()) {
                continue;
            }
            toolCalls.add(new AssistantMessage.ToolCall(
                    toolCall.id() == null || toolCall.id().isBlank() ? "call_" + toolCalls.size() : toolCall.id(),
                    toolCall.type() == null || toolCall.type().isBlank() ? "function" : toolCall.type(),
                    toolCall.name(),
                    toolCall.arguments() == null ? "{}" : toolCall.arguments()
            ));
        }
        return toolCalls;
    }

    private AgentExecutionCancelledException cancelled(Throwable cause) {
        return cause == null
                ? new AgentExecutionCancelledException("agent_execution_cancelled")
                : new AgentExecutionCancelledException("agent_execution_cancelled", cause);
    }

    private RuntimeException propagate(Exception error) {
        if (error instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException("spring_ai_call_failed", error);
    }

    private String summarizeRequest(List<LlmMessage> messages, List<ToolCallback> toolCallbacks) {
        if (messages == null && toolCallbacks == null) {
            return "null_request";
        }
        int messageCount = messages == null ? 0 : messages.size();
        int toolCount = toolCallbacks == null ? 0 : toolCallbacks.size();
        String lastUserMessage = "";
        if (messages != null) {
            for (int index = messages.size() - 1; index >= 0; index--) {
                LlmMessage message = messages.get(index);
                if (message != null && "user".equals(message.role())) {
                    lastUserMessage = previewText(message.content(), 80);
                    break;
                }
            }
        }
        return "provider=" + provider()
                + ", model=" + model()
                + ", messages=" + messageCount
                + ", tools=" + toolCount
                + ", lastUser=\"" + lastUserMessage + "\"";
    }

    private String previewText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    public record AgentCallContext(String conversationId, String knowledgeQuery, Integer knowledgeTopK) {
        public AgentCallContext(String conversationId) {
            this(conversationId, "", null);
        }

        public boolean hasConversationId() {
            return conversationId != null && !conversationId.isBlank();
        }

        public boolean hasKnowledgeQuery() {
            return knowledgeQuery != null && !knowledgeQuery.isBlank();
        }
    }
}
