package com.liuliu.citywalk.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.config.DeepSeekProperties;
import com.liuliu.citywalk.service.AgentContextWindowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class DeepSeekLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekLlmClient.class);
    private static final String PROVIDER = "deepseek";
    private static final String CONFIG_FALLBACK = "AI 服务暂未配置完成，我先根据现有数据给出基础建议。";
    private static final String RUNTIME_FALLBACK = "AI 规划暂时有点忙，我建议先从附近热门地点和社区攻略开始探索。";

    private final DeepSeekProperties properties;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<DeepSeekChatModel> chatModelProvider;
    private final AgentContextWindowService agentContextWindowService;

    public DeepSeekLlmClient(
            DeepSeekProperties properties,
            ObjectMapper objectMapper,
            ObjectProvider<DeepSeekChatModel> chatModelProvider,
            AgentContextWindowService agentContextWindowService
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.chatModelProvider = chatModelProvider;
        this.agentContextWindowService = agentContextWindowService;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public String model() {
        return properties.getModel();
    }

    @Override
    public boolean supportsNativeToolCalling() {
        return true;
    }

    @Override
    public LlmResponse createResponse(LlmRequest request) {
        DeepSeekChatModel chatModel = chatModelProvider.getIfAvailable();
        if (!isConfigured() || chatModel == null) {
            return fallbackResponse(CONFIG_FALLBACK, null);
        }

        try {
            return callSingleResponse(chatModel, request, null);
        } catch (Exception error) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw cancelled(error);
            }
            LlmResponse compactedRetry = tryContextOverflowRetry(chatModel, request, null, error);
            if (compactedRetry != null) {
                return compactedRetry;
            }
            log.warn(
                    "DeepSeek agent call failed. requestSummary={}. causeType={}: {}",
                    summarizeRequest(request),
                    error.getClass().getName(),
                    error.getMessage(),
                    error
            );
            return fallbackResponse(RUNTIME_FALLBACK, null);
        }
    }

    @Override
    public LlmResponse createStreamingResponse(LlmRequest request, LlmStreamListener listener) {
        DeepSeekChatModel chatModel = chatModelProvider.getIfAvailable();
        if (!isConfigured() || chatModel == null) {
            return fallbackResponse(CONFIG_FALLBACK, listener);
        }

        try {
            AtomicReference<ChatResponse> aggregatedResponse = new AtomicReference<>();
            new MessageAggregator()
                    .aggregate(chatModel.stream(toPrompt(request)), aggregatedResponse::set)
                    .doOnNext(chunk -> emitStreamingDelta(chunk, listener))
                    .blockLast();

            ChatResponse response = aggregatedResponse.get();
            if (response == null) {
                throw new IllegalStateException("deepseek_stream_empty");
            }
            return toLlmResponse(response);
        } catch (Exception error) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw cancelled(error);
            }
            LlmResponse compactedRetry = tryContextOverflowRetry(chatModel, request, listener, error);
            if (compactedRetry != null) {
                return compactedRetry;
            }
            log.warn(
                    "DeepSeek agent streaming call failed. requestSummary={}. causeType={}: {}. Falling back to single response mode.",
                    summarizeRequest(request),
                    error.getClass().getName(),
                    error.getMessage(),
                    error
            );
            try {
                return callSingleResponse(chatModel, request, listener);
            } catch (Exception fallbackError) {
                if (Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    throw cancelled(fallbackError);
                }
                log.warn(
                        "DeepSeek agent single-response fallback also failed. requestSummary={}. causeType={}: {}",
                        summarizeRequest(request),
                        fallbackError.getClass().getName(),
                        fallbackError.getMessage(),
                        fallbackError
                );
                return fallbackResponse(RUNTIME_FALLBACK, listener);
            }
        }
    }

    private LlmResponse tryContextOverflowRetry(
            DeepSeekChatModel chatModel,
            LlmRequest request,
            LlmStreamListener listener,
            Exception error
    ) {
        if (!isContextOverflowError(error)) {
            return null;
        }

        LlmRequest compactedRequest = agentContextWindowService.compactForContextOverflow(request);
        if (compactedRequest == null || compactedRequest.messages() == null || compactedRequest.messages().isEmpty()) {
            return null;
        }

        log.info(
                "DeepSeek request exceeded context window. Retrying with compacted context. originalSummary={}, compactedMessages={}",
                summarizeRequest(request),
                compactedRequest.messages().size()
        );
        try {
            return callSingleResponse(chatModel, compactedRequest, listener);
        } catch (Exception retryError) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw cancelled(retryError);
            }
            log.warn(
                    "DeepSeek compacted-context retry failed. requestSummary={}. causeType={}: {}",
                    summarizeRequest(compactedRequest),
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

    private LlmResponse callSingleResponse(DeepSeekChatModel chatModel, LlmRequest request, LlmStreamListener listener) {
        LlmResponse response = toLlmResponse(chatModel.call(toPrompt(request)));
        if (listener != null && response.content() != null && !response.content().isBlank()) {
            listener.onContentDelta(response.content());
        }
        return response;
    }

    private boolean isConfigured() {
        return properties.getApiKey() != null
                && !properties.getApiKey().isBlank()
                && properties.getBaseUrl() != null
                && !properties.getBaseUrl().isBlank();
    }

    private Prompt toPrompt(LlmRequest request) {
        return new Prompt(buildMessages(request), buildOptions(request));
    }

    private ToolCallingChatOptions buildOptions(LlmRequest request) {
        ToolCallingChatOptions.Builder builder = ToolCallingChatOptions.builder()
                .model(model())
                .internalToolExecutionEnabled(false)
                .temperature(request.temperature() == null ? 0.2 : request.temperature());
        List<ToolCallback> toolCallbacks = buildToolCallbacks(request.tools());
        if (!toolCallbacks.isEmpty()) {
            builder.toolCallbacks(toolCallbacks);
        }
        return builder.build();
    }

    private List<Message> buildMessages(LlmRequest request) {
        List<Message> messages = new ArrayList<>();
        if (request.instructions() != null && !request.instructions().isBlank()) {
            messages.add(new SystemMessage(request.instructions().trim()));
        }
        if (request.messages() == null || request.messages().isEmpty()) {
            return messages;
        }

        for (LlmMessage item : request.messages()) {
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

    private List<ToolCallback> buildToolCallbacks(List<LlmToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }

        List<ToolCallback> callbacks = new ArrayList<>(tools.size());
        for (LlmToolDefinition tool : tools) {
            if (tool == null || tool.name() == null || tool.name().isBlank()) {
                continue;
            }
            callbacks.add(new DefinitionOnlyToolCallback(tool, json(tool.inputSchema())));
        }
        return callbacks;
    }

    private void emitStreamingDelta(ChatResponse chunk, LlmStreamListener listener) {
        if (listener == null || chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
            return;
        }

        String delta = chunk.getResult().getOutput().getText();
        if (delta != null && !delta.isEmpty()) {
            listener.onContentDelta(delta);
        }
    }

    private LlmResponse toLlmResponse(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return new LlmResponse("", List.of(), null);
        }

        AssistantMessage output = response.getResult().getOutput();
        return new LlmResponse(
                output.getText() == null ? "" : output.getText(),
                extractToolCalls(output),
                json(response)
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

    private LlmResponse fallbackResponse(String content, LlmStreamListener listener) {
        if (listener != null && content != null && !content.isBlank()) {
            listener.onContentDelta(content);
        }
        return new LlmResponse(content, List.of(), null);
    }

    private AgentExecutionCancelledException cancelled(Throwable cause) {
        return cause == null
                ? new AgentExecutionCancelledException("agent_execution_cancelled")
                : new AgentExecutionCancelledException("agent_execution_cancelled", cause);
    }

    private String summarizeRequest(LlmRequest request) {
        if (request == null) {
            return "null_request";
        }
        int messageCount = request.messages() == null ? 0 : request.messages().size();
        int toolCount = request.tools() == null ? 0 : request.tools().size();
        String lastUserMessage = "";
        if (request.messages() != null) {
            for (int index = request.messages().size() - 1; index >= 0; index--) {
                LlmMessage message = request.messages().get(index);
                if (message != null && "user".equals(message.role())) {
                    lastUserMessage = previewText(message.content(), 80);
                    break;
                }
            }
        }
        return "model=" + model()
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

    private static final class DefinitionOnlyToolCallback implements ToolCallback {

        private final ToolDefinition toolDefinition;
        private final ToolMetadata toolMetadata;

        private DefinitionOnlyToolCallback(LlmToolDefinition definition, String inputSchema) {
            this.toolDefinition = ToolDefinition.builder()
                    .name(definition.name())
                    .description(definition.description())
                    .inputSchema(inputSchema == null || inputSchema.isBlank() ? "{}" : inputSchema)
                    .build();
            this.toolMetadata = ToolMetadata.builder().build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return toolDefinition;
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return toolMetadata;
        }

        @Override
        public String call(String toolInput) {
            throw new UnsupportedOperationException("tool_execution_managed_by_agent_orchestrator");
        }
    }
}
