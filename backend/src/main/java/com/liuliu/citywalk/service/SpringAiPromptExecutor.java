package com.liuliu.citywalk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Service
public class SpringAiPromptExecutor {

    private final ObjectMapper objectMapper;

    public SpringAiPromptExecutor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String callText(ChatModel chatModel, Prompt prompt) {
        String content = extractText(chatModel.call(prompt));
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("spring_ai_prompt_empty_content");
        }
        return content;
    }

    public <T> T callStructured(ChatModel chatModel, Prompt prompt, Class<T> responseType) {
        return outputConverter(responseType).convert(callText(chatModel, prompt));
    }

    public <T> T callStructuredStreaming(
            ChatModel chatModel,
            Prompt prompt,
            Consumer<String> listener,
            Class<T> responseType
    ) {
        return outputConverter(responseType).convert(callStreamingText(chatModel, prompt, listener));
    }

    public <T> String structuredFormat(Class<T> responseType) {
        return outputConverter(responseType).getFormat();
    }

    private String callStreamingText(ChatModel chatModel, Prompt prompt, Consumer<String> listener) {
        if (!(chatModel instanceof StreamingChatModel streamingChatModel)) {
            throw new IllegalStateException("streaming_chat_model_unavailable");
        }

        AtomicReference<ChatResponse> aggregatedResponse = new AtomicReference<>();
        new MessageAggregator()
                .aggregate(streamingChatModel.stream(prompt), aggregatedResponse::set)
                .doOnNext(chunk -> emitStreamingDelta(chunk, listener))
                .blockLast();

        String content = extractText(aggregatedResponse.get());
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("spring_ai_prompt_empty_streamed_content");
        }
        return content;
    }

    private void emitStreamingDelta(ChatResponse chunk, Consumer<String> listener) {
        if (listener == null) {
            return;
        }
        String delta = extractText(chunk);
        if (delta != null && !delta.isEmpty()) {
            listener.accept(delta);
        }
    }

    private String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        return response.getResult().getOutput().getText();
    }

    private <T> BeanOutputConverter<T> outputConverter(Class<T> responseType) {
        return new BeanOutputConverter<>(responseType, objectMapper);
    }
}
