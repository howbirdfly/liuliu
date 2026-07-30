package com.liuliu.citywalk.service.agent.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.model.dto.response.AgentStepResponse;
import com.liuliu.citywalk.service.AgentExecutionEvent;
import com.liuliu.citywalk.service.AgentIntentAnalysisService;
import com.liuliu.citywalk.service.AgentToolExecutionService;
import com.liuliu.citywalk.service.AgentToolResultSlicerService;
import com.liuliu.citywalk.service.agent.AgentExecutionCancelledException;
import com.liuliu.citywalk.service.agent.LlmMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DeterministicPrefetchHook implements AgentExecutionHook {

    private static final String PREFETCH_CODE = "deterministic_prefetch";

    private final ObjectMapper objectMapper;
    private final AgentToolExecutionService agentToolExecutionService;
    private final AgentToolResultSlicerService agentToolResultSlicerService;

    public DeterministicPrefetchHook(
            ObjectMapper objectMapper,
            AgentToolExecutionService agentToolExecutionService,
            AgentToolResultSlicerService agentToolResultSlicerService
    ) {
        this.objectMapper = objectMapper;
        this.agentToolExecutionService = agentToolExecutionService;
        this.agentToolResultSlicerService = agentToolResultSlicerService;
    }

    @Override
    public Set<AgentExecutionHookPoint> hookPoints() {
        return Set.of(AgentExecutionHookPoint.BEFORE_AGENT_LOOP);
    }

    @Override
    public void handle(AgentExecutionHookContext context) {
        PrefetchOutcome knowledgePrefetch = prefetchKnowledge(context);
        prefetchPoi(context, knowledgePrefetch);
    }

    private PrefetchOutcome prefetchKnowledge(AgentExecutionHookContext context) {
        AgentIntentAnalysisService.AgentIntent intent = context.intent();
        String prompt = safeText(context.normalizedPrompt(), "");
        if (!shouldPrefetchKnowledge(intent, prompt)) {
            return PrefetchOutcome.skipped();
        }
        if (!agentToolExecutionService.hasTool("search_knowledge_base")) {
            return PrefetchOutcome.skipped();
        }

        runCancellationCheck(context);
        String query = buildKnowledgePrefetchQuery(intent, prompt);
        if (query.isBlank()) {
            return PrefetchOutcome.skipped();
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
                    "System prefetch executed before planning: search_knowledge_base.",
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
            return PrefetchOutcome.executed(
                    toolCall.name(),
                    outcome.output(),
                    countResults(outcome.output()),
                    outcome.code() == null
            );
        } catch (AgentExecutionCancelledException error) {
            throw error;
        } catch (Exception error) {
            context.steps().add(new AgentStepResponse(
                    "prefetch_tool",
                    "search_knowledge_base",
                    prompt,
                    "knowledge_prefetch_skipped: " + safeText(error.getMessage(), "unknown_error")
            ));
            return PrefetchOutcome.skipped();
        }
    }

    private void prefetchPoi(AgentExecutionHookContext context, PrefetchOutcome knowledgePrefetch) {
        AgentIntentAnalysisService.AgentIntent intent = context.intent();
        String prompt = safeText(context.normalizedPrompt(), "");
        if (!shouldPrefetchPoi(intent, knowledgePrefetch)) {
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
        } catch (AgentExecutionCancelledException error) {
            throw error;
        } catch (Exception error) {
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

    private boolean shouldPrefetchPoi(
            AgentIntentAnalysisService.AgentIntent intent,
            PrefetchOutcome knowledgePrefetch
    ) {
        if (intent == null || !intent.needsRoutePlanning()) {
            return false;
        }
        if (intent.useCurrentLocation() || intent.missingLocationContext()) {
            return false;
        }
        if (knowledgePrefetch == null || !knowledgePrefetch.executed()) {
            return true;
        }
        if (!knowledgePrefetch.successful()) {
            return true;
        }
        return knowledgePrefetch.resultCount() < 2;
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

    private record PrefetchOutcome(
            boolean executed,
            String toolName,
            String output,
            int resultCount,
            boolean successful
    ) {
        private static PrefetchOutcome skipped() {
            return new PrefetchOutcome(false, null, null, 0, false);
        }

        private static PrefetchOutcome executed(
                String toolName,
                String output,
                int resultCount,
                boolean successful
        ) {
            return new PrefetchOutcome(true, toolName, output, resultCount, successful);
        }
    }
}
