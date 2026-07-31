package com.liuliu.citywalk.service;

import com.liuliu.citywalk.service.agent.AgentExecutionCancelledException;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Service
public class AgentAsyncPrefetchService {

    private final ConcurrentMap<String, KnowledgePrefetchState> knowledgePrefetchByExecutionId = new ConcurrentHashMap<>();

    public void startKnowledgePrefetch(
            String executionId,
            String operationId,
            AssistantMessage.ToolCall toolCall,
            Supplier<AgentToolExecutionService.AgentToolExecutionOutcome> supplier,
            Consumer<KnowledgePrefetchResult> completionListener
    ) {
        String normalizedExecutionId = normalize(executionId);
        if (normalizedExecutionId.isBlank() || toolCall == null || supplier == null) {
            return;
        }
        knowledgePrefetchByExecutionId.compute(normalizedExecutionId, (key, existing) -> {
            if (existing != null && !existing.future().isDone()) {
                return existing;
            }
            CompletableFuture<KnowledgePrefetchResult> future = new CompletableFuture<>();
            KnowledgePrefetchState state = new KnowledgePrefetchState(
                    operationId,
                    toolCall,
                    future,
                    new AtomicBoolean(false)
            );
            Thread.startVirtualThread(() -> {
                KnowledgePrefetchResult result;
                try {
                    AgentToolExecutionService.AgentToolExecutionOutcome outcome = supplier.get();
                    result = KnowledgePrefetchResult.completed(toolCall, outcome);
                } catch (AgentExecutionCancelledException cancelled) {
                    result = KnowledgePrefetchResult.cancelled(toolCall);
                } catch (Exception error) {
                    result = KnowledgePrefetchResult.failed(toolCall, error.getMessage());
                }
                future.complete(result);
                if (completionListener != null) {
                    completionListener.accept(result);
                }
            });
            return state;
        });
    }

    public KnowledgePrefetchResolution peekKnowledgePrefetch(String executionId) {
        KnowledgePrefetchState state = knowledgePrefetchByExecutionId.get(normalize(executionId));
        if (state == null) {
            return KnowledgePrefetchResolution.notFound();
        }
        KnowledgePrefetchResult result = state.future().getNow(null);
        if (result == null) {
            return KnowledgePrefetchResolution.pending(state.operationId(), state.toolCall());
        }
        return KnowledgePrefetchResolution.ready(state.operationId(), state.toolCall(), result);
    }

    public KnowledgePrefetchResolution awaitKnowledgePrefetch(String executionId, Duration waitDuration) {
        KnowledgePrefetchState state = knowledgePrefetchByExecutionId.get(normalize(executionId));
        if (state == null) {
            return KnowledgePrefetchResolution.notFound();
        }
        long waitMillis = waitDuration == null ? 0L : Math.max(0L, waitDuration.toMillis());
        try {
            KnowledgePrefetchResult result = waitMillis <= 0L
                    ? state.future().getNow(null)
                    : state.future().get(waitMillis, TimeUnit.MILLISECONDS);
            if (result == null) {
                return KnowledgePrefetchResolution.pending(state.operationId(), state.toolCall());
            }
            return KnowledgePrefetchResolution.ready(state.operationId(), state.toolCall(), result);
        } catch (TimeoutException ignored) {
            return KnowledgePrefetchResolution.pending(state.operationId(), state.toolCall());
        } catch (Exception error) {
            return KnowledgePrefetchResolution.ready(
                    state.operationId(),
                    state.toolCall(),
                    KnowledgePrefetchResult.failed(state.toolCall(), error.getMessage())
            );
        }
    }

    public boolean markKnowledgePrefetchInjected(String executionId) {
        KnowledgePrefetchState state = knowledgePrefetchByExecutionId.get(normalize(executionId));
        return state != null && state.injected().compareAndSet(false, true);
    }

    public void clearExecution(String executionId) {
        KnowledgePrefetchState state = knowledgePrefetchByExecutionId.remove(normalize(executionId));
        if (state == null) {
            return;
        }
        state.future().cancel(true);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private record KnowledgePrefetchState(
            String operationId,
            AssistantMessage.ToolCall toolCall,
            CompletableFuture<KnowledgePrefetchResult> future,
            AtomicBoolean injected
    ) {
    }

    public record KnowledgePrefetchResolution(
            boolean found,
            boolean pending,
            String operationId,
            AssistantMessage.ToolCall toolCall,
            KnowledgePrefetchResult result
    ) {
        public static KnowledgePrefetchResolution notFound() {
            return new KnowledgePrefetchResolution(false, false, "", null, null);
        }

        public static KnowledgePrefetchResolution pending(String operationId, AssistantMessage.ToolCall toolCall) {
            return new KnowledgePrefetchResolution(true, true, operationId, toolCall, null);
        }

        public static KnowledgePrefetchResolution ready(
                String operationId,
                AssistantMessage.ToolCall toolCall,
                KnowledgePrefetchResult result
        ) {
            return new KnowledgePrefetchResolution(true, false, operationId, toolCall, result);
        }
    }

    public record KnowledgePrefetchResult(
            AssistantMessage.ToolCall toolCall,
            AgentToolExecutionService.AgentToolExecutionOutcome outcome,
            boolean cancelled,
            String errorMessage
    ) {
        public static KnowledgePrefetchResult completed(
                AssistantMessage.ToolCall toolCall,
                AgentToolExecutionService.AgentToolExecutionOutcome outcome
        ) {
            return new KnowledgePrefetchResult(toolCall, outcome, false, "");
        }

        public static KnowledgePrefetchResult cancelled(AssistantMessage.ToolCall toolCall) {
            return new KnowledgePrefetchResult(toolCall, null, true, "");
        }

        public static KnowledgePrefetchResult failed(AssistantMessage.ToolCall toolCall, String errorMessage) {
            return new KnowledgePrefetchResult(toolCall, null, false, errorMessage == null ? "" : errorMessage.trim());
        }

        public boolean successful() {
            return !cancelled && outcome != null && outcome.code() == null;
        }
    }
}
