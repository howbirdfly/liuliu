package com.liuliu.citywalk.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.service.AgentContextWindowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.model.StreamingChatModel;
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

    private final ObjectMapper objectMapper;
    private final ChatModel chatModel;
    private final AgentContextWindowService agentContextWindowService;
    private final String configuredProvider;
    private final String configuredModel;
    private final String configuredApiKey;
    private final String configuredBaseUrl;

    public SpringAiLlmClient(
            ObjectMapper objectMapper,
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            AgentContextWindowService agentContextWindowService,
            @Value("${spring.ai.model.chat:deepseek}") String configuredProvider,
            @Value("${spring.ai.deepseek.chat.model:deepseek-chat}") String configuredModel,
            @Value("${spring.ai.deepseek.api-key:}") String configuredApiKey,
            @Value("${spring.ai.deepseek.base-url:https://api.deepseek.com}") String configuredBaseUrl
    ) {
        this.objectMapper = objectMapper;
        this.chatModel = chatModel;
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
        ChatModel chatModel = requireChatModel();

        try {
            return callSingleResponse(chatModel, instructions, messages, toolCallbacks, temperature, null);
        } catch (Exception error) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw cancelled(error);
            }
            LlmResponse compactedRetry = tryContextOverflowRetry(
                    chatModel,
                    instructions,
                    messages,
                    toolCallbacks,
                    temperature,
                    null,
                    error
            );
            if (compactedRetry != null) {
                return compactedRetry;
            }
            log.warn(
                    "Spring AI agent call failed. requestSummary={}. causeType={}: {}",
                    summarizeRequest(messages, toolCallbacks),
                    error.getClass().getName(),
                    error.getMessage(),
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
        ChatModel chatModel = requireChatModel();
        StreamingChatModel streamingChatModel = requireStreamingChatModel(chatModel);

        try {
            AtomicReference<ChatResponse> aggregatedResponse = new AtomicReference<>();
            new MessageAggregator()
                    .aggregate(streamingChatModel.stream(toPrompt(instructions, messages, toolCallbacks, temperature)), aggregatedResponse::set)
                    .doOnNext(chunk -> emitStreamingDelta(chunk, listener))
                    .blockLast();

            ChatResponse response = aggregatedResponse.get();
            if (response == null) {
                throw new IllegalStateException("spring_ai_stream_empty");
            }
            return toLlmResponse(response, true);
        } catch (Exception error) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw cancelled(error);
            }
            LlmResponse compactedRetry = tryContextOverflowRetry(
                    chatModel,
                    instructions,
                    messages,
                    toolCallbacks,
                    temperature,
                    listener,
                    error
            );
            if (compactedRetry != null) {
                return compactedRetry;
            }
            log.warn(
                    "Spring AI agent streaming call failed. requestSummary={}. causeType={}: {}",
                    summarizeRequest(messages, toolCallbacks),
                    error.getClass().getName(),
                    error.getMessage(),
                    error
            );
            throw propagate(error);
        }
    }

    private LlmResponse tryContextOverflowRetry(
            ChatModel chatModel,
            String instructions,
            List<LlmMessage> messages,
            List<ToolCallback> toolCallbacks,
            Double temperature,
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
            return callSingleResponse(chatModel, instructions, compactedMessages, toolCallbacks, temperature, listener);
        } catch (Exception retryError) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw cancelled(retryError);
            }
            log.warn(
                    "Spring AI compacted-context retry failed. requestSummary={}. causeType={}: {}",
                    summarizeRequest(compactedMessages, toolCallbacks),
                    retryError.getClass().getName(),
                    retryError.getMessage(),
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
            ChatModel chatModel,
            String instructions,
            List<LlmMessage> messages,
            List<ToolCallback> toolCallbacks,
            Double temperature,
            Consumer<String> listener
    ) {
        LlmResponse response = toLlmResponse(chatModel.call(toPrompt(instructions, messages, toolCallbacks, temperature)), false);
        if (listener != null && response.content() != null && !response.content().isBlank()) {
            listener.accept(response.content());
        }
        return response;
    }

    private boolean isConfigured() {
        return !configuredApiKey.isBlank() && !configuredBaseUrl.isBlank();
    }

    private ChatModel resolveChatModel() {
        return chatModel;
    }

    private ChatModel requireChatModel() {
        if (!isConfigured()) {
            throw new IllegalStateException("spring_ai_chat_not_configured");
        }
        ChatModel chatModel = resolveChatModel();
        if (chatModel == null) {
            throw new IllegalStateException("spring_ai_chat_model_unavailable");
        }
        return chatModel;
    }

    private StreamingChatModel resolveStreamingChatModel(ChatModel chatModel) {
        if (chatModel instanceof StreamingChatModel compatibleStreamingModel) {
            return compatibleStreamingModel;
        }
        return null;
    }

    private StreamingChatModel requireStreamingChatModel(ChatModel chatModel) {
        StreamingChatModel streamingChatModel = resolveStreamingChatModel(chatModel);
        if (streamingChatModel == null) {
            throw new IllegalStateException("spring_ai_streaming_chat_model_unavailable");
        }
        return streamingChatModel;
    }

    private Prompt toPrompt(
            String instructions,
            List<LlmMessage> messages,
            List<ToolCallback> toolCallbacks,
            Double temperature
    ) {
        return new Prompt(buildMessages(instructions, messages), buildOptions(toolCallbacks, temperature));
    }

    private ToolCallingChatOptions buildOptions(List<ToolCallback> toolCallbacks, Double temperature) {
        ToolCallingChatOptions.Builder builder = ToolCallingChatOptions.builder()
                .model(model())
                .internalToolExecutionEnabled(false)
                .temperature(temperature == null ? 0.2 : temperature);
        List<ToolCallback> normalizedToolCallbacks = toolCallbacks == null ? List.of() : toolCallbacks;
        if (!normalizedToolCallbacks.isEmpty()) {
            builder.toolCallbacks(normalizedToolCallbacks);
        }
        return builder.build();
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
                    .toolCalls(toSpringAiToolCalls(item.toolCalls()))
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

    private List<AssistantMessage.ToolCall> toSpringAiToolCalls(List<LlmToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }

        List<AssistantMessage.ToolCall> result = new ArrayList<>(toolCalls.size());
        for (LlmToolCall toolCall : toolCalls) {
            if (toolCall == null || toolCall.name() == null || toolCall.name().isBlank()) {
                continue;
            }
            result.add(new AssistantMessage.ToolCall(
                    toolCall.id(),
                    "function",
                    toolCall.name(),
                    toolCall.argumentsJson() == null ? "{}" : toolCall.argumentsJson()
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

    private LlmResponse toLlmResponse(ChatResponse response, boolean streaming) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return new LlmResponse("", List.of(), null, new LlmResponseMetadata(provider(), model(), streaming, null));
        }

        AssistantMessage output = response.getResult().getOutput();
        return new LlmResponse(
                output.getText() == null ? "" : output.getText(),
                extractToolCalls(output),
                json(response),
                new LlmResponseMetadata(provider(), model(), streaming, json(response.getMetadata()))
        );
    }

    private List<LlmToolCall> extractToolCalls(AssistantMessage message) {
        if (message == null || !message.hasToolCalls()) {
            return List.of();
        }

        List<LlmToolCall> toolCalls = new ArrayList<>();
        for (AssistantMessage.ToolCall toolCall : message.getToolCalls()) {
            if (toolCall == null || toolCall.name() == null || toolCall.name().isBlank()) {
                continue;
            }
            toolCalls.add(new LlmToolCall(
                    toolCall.id() == null || toolCall.id().isBlank() ? "call_" + toolCalls.size() : toolCall.id(),
                    toolCall.name(),
                    toolCall.arguments() == null ? "{}" : toolCall.arguments()
            ));
        }
        return toolCalls;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            return null;
        }
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
}
