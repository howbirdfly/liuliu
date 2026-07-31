package com.liuliu.citywalk.service.agent.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.model.dto.response.AgentStepResponse;
import com.liuliu.citywalk.service.AgentAsyncPrefetchService;
import com.liuliu.citywalk.service.AgentExecutionEvent;
import com.liuliu.citywalk.service.AgentIntentAnalysisService;
import com.liuliu.citywalk.service.AgentToolExecutionService;
import com.liuliu.citywalk.service.AgentToolResultSlicerService;
import com.liuliu.citywalk.service.agent.AgentExecutionCancelledException;
import com.liuliu.citywalk.service.agent.LlmMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DeterministicPrefetchHook implements AgentExecutionHook {

    private static final String PREFETCH_CODE = "deterministic_prefetch";
    private static final String PROGRESS_EVENT_TYPE = "progress";
    private static final String KNOWLEDGE_OPERATION_ID = "prefetch:search_knowledge_base";
    private static final String POI_OPERATION_ID = "prefetch:search_poi";
    private static final Duration FIRST_ROUND_KNOWLEDGE_WAIT = Duration.ofMillis(900);

    private final ObjectMapper objectMapper;
    private final AgentToolExecutionService agentToolExecutionService;
    private final AgentToolResultSlicerService agentToolResultSlicerService;
    private final AgentAsyncPrefetchService agentAsyncPrefetchService;

    public DeterministicPrefetchHook(
            ObjectMapper objectMapper,
            AgentToolExecutionService agentToolExecutionService,
            AgentToolResultSlicerService agentToolResultSlicerService,
            AgentAsyncPrefetchService agentAsyncPrefetchService
    ) {
        this.objectMapper = objectMapper;
        this.agentToolExecutionService = agentToolExecutionService;
        this.agentToolResultSlicerService = agentToolResultSlicerService;
        this.agentAsyncPrefetchService = agentAsyncPrefetchService;
    }

    @Override
    public Set<AgentExecutionHookPoint> hookPoints() {
        return Set.of(
                AgentExecutionHookPoint.BEFORE_AGENT_LOOP,
                AgentExecutionHookPoint.BEFORE_LLM_CALL,
                AgentExecutionHookPoint.AFTER_AGENT_LOOP
        );
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public AgentExecutionHookResult handle(AgentExecutionHookContext context) {
        if (context == null) {
            return AgentExecutionHookResult.continueExecution();
        }
        return switch (context.point()) {
            case BEFORE_AGENT_LOOP -> handleBeforeAgentLoop(context);
            case BEFORE_LLM_CALL -> handleBeforeLlmCall(context);
            case AFTER_AGENT_LOOP -> handleAfterAgentLoop(context);
            default -> AgentExecutionHookResult.continueExecution();
        };
    }

    private AgentExecutionHookResult handleBeforeAgentLoop(AgentExecutionHookContext context) {
        startAsyncKnowledgePrefetch(context);
        prefetchPoi(context);
        injectKnowledgePrefetchIfReady(context, Duration.ZERO);
        return AgentExecutionHookResult.continueExecution();
    }

    private AgentExecutionHookResult handleBeforeLlmCall(AgentExecutionHookContext context) {
        Duration waitBudget = context.round() <= 1 ? FIRST_ROUND_KNOWLEDGE_WAIT : Duration.ZERO;
        injectKnowledgePrefetchIfReady(context, waitBudget);
        return AgentExecutionHookResult.continueExecution();
    }

    private AgentExecutionHookResult handleAfterAgentLoop(AgentExecutionHookContext context) {
        agentAsyncPrefetchService.clearExecution(context.executionId());
        return AgentExecutionHookResult.continueExecution();
    }

    private void startAsyncKnowledgePrefetch(AgentExecutionHookContext context) {
        AgentIntentAnalysisService.AgentIntent intent = context.intent();
        String prompt = safeText(context.normalizedPrompt(), "");
        if (!shouldPrefetchKnowledge(intent, prompt)) {
            return;
        }
        if (!agentToolExecutionService.hasTool("search_knowledge_base")) {
            return;
        }

        runCancellationCheck(context);
        String query = buildKnowledgePrefetchQuery(intent, prompt);
        if (query.isBlank()) {
            return;
        }

        try {
            String argumentsJson = objectMapper.writeValueAsString(Map.of(
                    "query", query,
                    "topK", 5
            ));
            AssistantMessage.ToolCall toolCall = toolCall(
                    "prefetch-knowledge-" + UUID.randomUUID(),
                    "search_knowledge_base",
                    argumentsJson
            );
            context.emit(progressEvent(
                    context,
                    KNOWLEDGE_OPERATION_ID,
                    "started",
                    "正在检索知识库候选内容..."
            ));
            context.emit(new AgentExecutionEvent(
                    "tool_call",
                    toolCall.name(),
                    toolCall.arguments(),
                    null,
                    0,
                    context.provider(),
                    context.model(),
                    PREFETCH_CODE
            ));
            agentAsyncPrefetchService.startKnowledgePrefetch(
                    context.executionId(),
                    KNOWLEDGE_OPERATION_ID,
                    toolCall,
                    () -> agentToolExecutionService.executePrefetched(
                            toolCall.name(),
                            toolCall.arguments(),
                            () -> runCancellationCheck(context),
                            context.toolExecutionMemoByKey()
                    ),
                    result -> emitKnowledgeCompletionProgress(context, result)
            );
        } catch (Exception error) {
            context.emit(progressEvent(
                    context,
                    KNOWLEDGE_OPERATION_ID,
                    "failed",
                    "知识库预取启动失败，已跳过本次预取。"
            ));
            context.steps().add(new AgentStepResponse(
                    "prefetch_tool",
                    "search_knowledge_base",
                    prompt,
                    "knowledge_prefetch_skipped: " + safeText(error.getMessage(), "unknown_error")
            ));
        }
    }

    private void injectKnowledgePrefetchIfReady(AgentExecutionHookContext context, Duration waitBudget) {
        AgentAsyncPrefetchService.KnowledgePrefetchResolution resolution =
                waitBudget == null || waitBudget.isZero()
                        ? agentAsyncPrefetchService.peekKnowledgePrefetch(context.executionId())
                        : agentAsyncPrefetchService.awaitKnowledgePrefetch(context.executionId(), waitBudget);
        if (!resolution.found() || resolution.pending()) {
            return;
        }
        if (!agentAsyncPrefetchService.markKnowledgePrefetchInjected(context.executionId())) {
            return;
        }

        AgentAsyncPrefetchService.KnowledgePrefetchResult result = resolution.result();
        AssistantMessage.ToolCall toolCall = result == null ? resolution.toolCall() : result.toolCall();
        AgentToolExecutionService.AgentToolExecutionOutcome outcome = result == null ? null : result.outcome();
        if (toolCall == null || outcome == null) {
            return;
        }

        context.steps().add(new AgentStepResponse(
                "prefetch_tool",
                toolCall.name(),
                toolCall.arguments(),
                outcome.output()
        ));
        context.messages().add(LlmMessage.assistant(
                "System prefetch completed before planning: search_knowledge_base.",
                List.of(toolCall)
        ));
        context.messages().add(LlmMessage.tool(
                toolCall.id(),
                toolCall.name(),
                agentToolResultSlicerService.sliceForModel(toolCall.name(), outcome.output())
        ));
        context.emit(new AgentExecutionEvent(
                "tool_result",
                toolCall.name(),
                toolCall.arguments(),
                outcome.output(),
                0,
                context.provider(),
                context.model(),
                outcome.code() == null ? PREFETCH_CODE : outcome.code()
        ));
    }

    private void emitKnowledgeCompletionProgress(
            AgentExecutionHookContext context,
            AgentAsyncPrefetchService.KnowledgePrefetchResult result
    ) {
        if (result == null || result.cancelled()) {
            return;
        }
        if (result.outcome() != null) {
            context.emit(progressEvent(
                    context,
                    KNOWLEDGE_OPERATION_ID,
                    result.successful() ? "completed" : "failed",
                    result.successful()
                            ? "知识库预取完成，命中 " + countResults(result.outcome().output()) + " 条候选。"
                            : "知识库预取未拿到稳定结果。"
            ));
            return;
        }
        context.emit(progressEvent(
                context,
                KNOWLEDGE_OPERATION_ID,
                "failed",
                "知识库预取失败，已跳过本次预取。"
        ));
    }

    private void prefetchPoi(AgentExecutionHookContext context) {
        AgentIntentAnalysisService.AgentIntent intent = context.intent();
        String prompt = safeText(context.normalizedPrompt(), "");
        if (!shouldPrefetchPoi(intent)) {
            return;
        }
        if (!agentToolExecutionService.hasTool("search_poi")) {
            return;
        }

        runCancellationCheck(context);
        String query = buildPoiPrefetchQuery(intent, prompt);
        if (query.isBlank()) {
            return;
        }

        try {
            String argumentsJson = objectMapper.writeValueAsString(Map.of("query", query));
            context.emit(progressEvent(
                    context,
                    POI_OPERATION_ID,
                    "started",
                    "正在补充检索候选 POI..."
            ));
            AssistantMessage.ToolCall toolCall = toolCall(
                    "prefetch-poi-" + UUID.randomUUID(),
                    "search_poi",
                    argumentsJson
            );

            context.emit(new AgentExecutionEvent(
                    "tool_call",
                    toolCall.name(),
                    toolCall.arguments(),
                    null,
                    0,
                    context.provider(),
                    context.model(),
                    PREFETCH_CODE
            ));
            AgentToolExecutionService.AgentToolExecutionOutcome outcome = agentToolExecutionService.executePrefetched(
                    toolCall.name(),
                    toolCall.arguments(),
                    () -> runCancellationCheck(context),
                    context.toolExecutionMemoByKey()
            );
            context.steps().add(new AgentStepResponse(
                    "prefetch_tool",
                    toolCall.name(),
                    toolCall.arguments(),
                    outcome.output()
            ));
            context.messages().add(LlmMessage.assistant(
                    "System prefetch executed before planning: search_poi.",
                    List.of(toolCall)
            ));
            context.messages().add(LlmMessage.tool(
                    toolCall.id(),
                    toolCall.name(),
                    agentToolResultSlicerService.sliceForModel(toolCall.name(), outcome.output())
            ));
            context.emit(new AgentExecutionEvent(
                    "tool_result",
                    toolCall.name(),
                    toolCall.arguments(),
                    outcome.output(),
                    0,
                    context.provider(),
                    context.model(),
                    outcome.code() == null ? PREFETCH_CODE : outcome.code()
            ));
            context.emit(progressEvent(
                    context,
                    POI_OPERATION_ID,
                    outcome.code() == null ? "completed" : "failed",
                    outcome.code() == null
                            ? "POI 预取完成，命中 " + countResults(outcome.output()) + " 条候选。"
                            : "POI 预取未拿到稳定结果。"
            ));
        } catch (AgentExecutionCancelledException error) {
            throw error;
        } catch (Exception error) {
            context.emit(progressEvent(
                    context,
                    POI_OPERATION_ID,
                    "failed",
                    "POI 预取失败，已跳过本次预取。"
            ));
            context.steps().add(new AgentStepResponse(
                    "prefetch_tool",
                    "search_poi",
                    prompt,
                    "poi_prefetch_skipped: " + safeText(error.getMessage(), "unknown_error")
            ));
        }
    }

    private boolean shouldPrefetchKnowledge(AgentIntentAnalysisService.AgentIntent intent, String prompt) {
        if (intent == null || prompt.isBlank()) {
            return false;
        }
        if (intent.needsThemeGeneration() && !intent.needsRoutePlanning() && !intent.needsKnowledgeReference()) {
            return false;
        }
        return intent.needsKnowledgeReference() || intent.needsRoutePlanning();
    }

    private String buildKnowledgePrefetchQuery(AgentIntentAnalysisService.AgentIntent intent, String prompt) {
        if (intent == null) {
            return prompt;
        }
        List<String> parts = new ArrayList<>();
        appendIntentParts(parts, intent);
        if (intent.needsRoutePlanning()) {
            parts.add("city walk route");
        } else if (intent.needsKnowledgeReference()) {
            parts.add("city walk reference");
        }
        String query = String.join(" ", parts).trim();
        return query.isBlank() ? prompt : query;
    }

    private boolean shouldPrefetchPoi(AgentIntentAnalysisService.AgentIntent intent) {
        if (intent == null || !intent.needsRoutePlanning()) {
            return false;
        }
        if (intent.useCurrentLocation() || intent.missingLocationContext()) {
            return false;
        }
        return true;
    }

    private String buildPoiPrefetchQuery(AgentIntentAnalysisService.AgentIntent intent, String prompt) {
        if (intent == null) {
            return prompt;
        }
        List<String> parts = new ArrayList<>();
        appendIntentParts(parts, intent);
        parts.add("city walk poi");
        String query = String.join(" ", parts).trim();
        return query.isBlank() ? prompt : query;
    }

    private void appendIntentParts(List<String> parts, AgentIntentAnalysisService.AgentIntent intent) {
        if (!intent.cities().isEmpty()) {
            parts.add(String.join(" ", intent.cities()));
        }
        if (!intent.areas().isEmpty()) {
            parts.add(String.join(" ", intent.areas()));
        }
        if (!intent.styles().isEmpty()) {
            parts.add(String.join(" ", intent.styles()));
        }
        if (!intent.objectives().isEmpty()) {
            parts.add(String.join(" ", intent.objectives()));
        }
        if (!intent.timePreference().isBlank()) {
            parts.add(intent.timePreference());
        }
        if (!intent.duration().isBlank()) {
            parts.add(intent.duration());
        }
    }

    private int countResults(String output) {
        if (output == null || output.isBlank()) {
            return 0;
        }
        try {
            Object value = objectMapper.readValue(output, Object.class);
            if (!(value instanceof Map<?, ?> map)) {
                return 0;
            }
            Object results = map.get("results");
            if (results instanceof Iterable<?> iterable) {
                int count = 0;
                for (Object ignored : iterable) {
                    count++;
                }
                return count;
            }
            if (results != null && results.getClass().isArray()) {
                return java.lang.reflect.Array.getLength(results);
            }
            return 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private AssistantMessage.ToolCall toolCall(String id, String name, String argumentsJson) {
        return new AssistantMessage.ToolCall(
                id,
                "function",
                name,
                argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson
        );
    }

    private void runCancellationCheck(AgentExecutionHookContext context) {
        if (context == null || context.cancellationCheck() == null) {
            return;
        }
        context.cancellationCheck().run();
    }

    private String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private AgentExecutionEvent progressEvent(
            AgentExecutionHookContext context,
            String operationId,
            String phase,
            String message
    ) {
        return new AgentExecutionEvent(
                PROGRESS_EVENT_TYPE,
                "prefetch",
                null,
                null,
                0,
                context == null ? null : context.provider(),
                context == null ? null : context.model(),
                PREFETCH_CODE,
                operationId,
                phase,
                message
        );
    }
}
