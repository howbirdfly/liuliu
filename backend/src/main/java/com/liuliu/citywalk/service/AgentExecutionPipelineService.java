package com.liuliu.citywalk.service;

import com.liuliu.citywalk.model.dto.response.AgentChatResponse;
import com.liuliu.citywalk.model.dto.response.AgentStepResponse;
import com.liuliu.citywalk.service.agent.AgentExecutionCancelledException;
import com.liuliu.citywalk.service.agent.LlmMessage;
import com.liuliu.citywalk.service.agent.SpringAiLlmClient;
import com.liuliu.citywalk.service.agent.hook.AgentExecutionHookContext;
import com.liuliu.citywalk.service.agent.hook.AgentExecutionHookPoint;
import com.liuliu.citywalk.service.agent.hook.AgentExecutionHookResult;
import com.liuliu.citywalk.service.agent.hook.AgentExecutionHookRegistryService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentExecutionPipelineService {

    private static final int MAX_TOOL_ROUNDS = 6;
    private static final String ATTR_REMEMBER_SHORT_TERM_ONLY = "rememberShortTermOnly";
    private static final String ATTR_COMPLETION_CODE = "completionCode";

    private static final String PIPELINE_GUIDE = """

            Follow this planning pipeline for every request:
            1. Read the structured intent, user constraints, and any missing slots first.
            2. Unless the user is only brainstorming a theme, prefer calling search_knowledge_base first to gather real City Walk references.
            3. If the knowledge-base results are not enough for concrete stops, route order, or nearby options, then call map tools such as search_poi or nearby_pois.
            4. Use get_walk_detail only when a referenced public walk needs more detail.
            5. Avoid repeated overlapping tool calls when existing results already answer the question.
            6. Never fabricate exact places when tools did not confirm them.
            """;

    private static final String FINAL_ANSWER_GUIDE = """

            Final answer contract:
            - Use markdown and keep these exact sections in order:
              ## 推荐区域
              ## 路线顺序
              ## 依据来源
              ## 不确定项
              ## 实用提醒
            - In 推荐区域, say the best area or route direction first and briefly explain why it fits.
            - In 路线顺序, give a short ordered walking sequence when possible.
            - In 依据来源, distinguish tool-confirmed references from general synthesis.
            - In 不确定项, explicitly mention what is inferred or still needs live confirmation.
            - In 实用提醒, end with one or two practical tips.
            """;

    private static final String FALLBACK_GUIDE = """

            如果工具返回 success=false，或者包含 error / fallbackSuggestion 字段，说明这一步没有拿到可靠的工具结果。
            这种情况下不要假装拿到了真实数据，要结合已有上下文、其他工具结果和常识继续给出保守建议，并明确说明哪些信息缺少工具支撑。
            """;

    private static final String DEFAULT_INSTRUCTIONS = """
            你是 Liuliu City Walk 的智能规划 Agent。
            你的目标是根据用户的自然语言需求，尽量结合工具结果生成可执行的城市漫步建议。
            当你缺少地点、社区攻略或路线细节时，优先调用工具，不要凭空编造具体地点。
            如果用户在问有没有类似路线参考、别人怎么走、适合海边日落、校园散步、老街拍照的路线，
            或者明显需要参考公开帖子和已有路线时，优先调用 search_knowledge_base 检索知识库，再结合地图工具补地点。
            如果知识库已经命中相关帖子，最终回答里要尽量吸收这些真实内容，但不要逐字照抄原文。
            如果工具返回的结果不足，也要明确告诉用户哪些内容是推测，哪些内容来自工具。
            最终回答尽量包含：适合的区域、推荐理由、可逛兴趣点、建议路线顺序，以及一两句贴心提醒。
            """;

    private static final String CONVERSATION_STATE_RULES = """

            Conversation-state rules:
            - Always treat the effective conversation state as the main source of truth for location, theme, and duration.
            - Judge completeness using the merged state of the whole conversation, not only the latest user sentence.
            - Short user follow-ups are usually incremental updates to the active request, not brand-new standalone requests.
            - Examples of valid incremental updates include "从桂园出口", "一小时", "还有别的吗", "改成晚上", and "换个安静一点的".
            - If the latest user sentence only updates one slot, keep the other ready slots from the effective conversation state.
            - Do not ask again for a slot that is already ready in the effective conversation state unless the user explicitly changes or resets it.
            - Only treat the turn as a brand-new request when the user clearly starts over or explicitly asks to ignore earlier context.
            """;

    private final SpringAiLlmClient llmClient;
    private final AgentIntentAnalysisService agentIntentAnalysisService;
    private final AgentConversationStateService agentConversationStateService;
    private final AgentPromptAssemblyService agentPromptAssemblyService;
    private final AgentAnswerFormatterService agentAnswerFormatterService;
    private final AgentToolExecutionService agentToolExecutionService;
    private final AgentToolResultSlicerService agentToolResultSlicerService;
    private final AgentRoundService agentRoundService;
    private final AgentExecutionHookRegistryService agentExecutionHookRegistryService;

    public AgentExecutionPipelineService(
            SpringAiLlmClient llmClient,
            AgentIntentAnalysisService agentIntentAnalysisService,
            AgentConversationStateService agentConversationStateService,
            AgentPromptAssemblyService agentPromptAssemblyService,
            AgentAnswerFormatterService agentAnswerFormatterService,
            AgentToolExecutionService agentToolExecutionService,
            AgentToolResultSlicerService agentToolResultSlicerService,
            AgentRoundService agentRoundService,
            AgentExecutionHookRegistryService agentExecutionHookRegistryService
    ) {
        this.llmClient = llmClient;
        this.agentIntentAnalysisService = agentIntentAnalysisService;
        this.agentConversationStateService = agentConversationStateService;
        this.agentPromptAssemblyService = agentPromptAssemblyService;
        this.agentAnswerFormatterService = agentAnswerFormatterService;
        this.agentToolExecutionService = agentToolExecutionService;
        this.agentToolResultSlicerService = agentToolResultSlicerService;
        this.agentRoundService = agentRoundService;
        this.agentExecutionHookRegistryService = agentExecutionHookRegistryService;
    }

    public AgentChatResponse execute(
            Long userId,
            String prompt,
            AgentExecutionRegistryService.AgentExecutionHandle executionHandle,
            AgentExecutionListener listener
    ) {
        PreparedExecution execution = prepareExecution(userId, prompt, executionHandle, listener);
        return runPlanningLoop(execution, executionHandle, listener);
    }

    private PreparedExecution prepareExecution(
            Long userId,
            String prompt,
            AgentExecutionRegistryService.AgentExecutionHandle executionHandle,
            AgentExecutionListener listener
    ) {
        String normalizedPrompt = prompt == null ? "" : prompt.trim();
        checkCancelled(executionHandle);

        List<LlmMessage> history = agentPromptAssemblyService.loadConversationHistory(userId);
        AgentConversationStateService.ResolvedConversationState conversationState =
                agentConversationStateService.resolve(userId, history, normalizedPrompt);
        AgentIntentAnalysisService.AgentIntent intent = conversationState.effectiveIntent();
        String carryoverContext = conversationState.carryoverPromptContext();
        String stateMessage = agentConversationStateService.buildStateMessage(conversationState);
        List<LlmMessage> messages = agentPromptAssemblyService.buildCurrentTurnMessages(
                normalizedPrompt,
                carryoverContext,
                stateMessage
        );
        String instructions = agentPromptAssemblyService.buildInstructions(List.of(
                AgentPromptAssemblyService.section("identity", DEFAULT_INSTRUCTIONS),
                AgentPromptAssemblyService.section("conversation_state_rules", CONVERSATION_STATE_RULES),
                AgentPromptAssemblyService.section(
                        "conversation_state_runtime",
                        agentConversationStateService.buildPromptContext(conversationState)
                ),
                AgentPromptAssemblyService.section(
                        "intent_runtime_context",
                        agentIntentAnalysisService.buildPromptContext(intent)
                ),
                AgentPromptAssemblyService.section("planning_pipeline", PIPELINE_GUIDE),
                AgentPromptAssemblyService.section(
                        "request_pipeline_focus",
                        buildIntentSpecificPipelineGuide(intent)
                ),
                AgentPromptAssemblyService.section("final_answer_contract", FINAL_ANSWER_GUIDE),
                agentPromptAssemblyService.buildLongTermMemorySection(userId, normalizedPrompt, intent),
                AgentPromptAssemblyService.section("fallback_rules", FALLBACK_GUIDE)
        ));

        List<AgentStepResponse> steps = new ArrayList<>();
        steps.add(new AgentStepResponse(
                "intent_analysis",
                "intent",
                normalizedPrompt,
                agentIntentAnalysisService.toStepOutput(intent)
        ));
        steps.add(new AgentStepResponse(
                "conversation_state",
                "effective_conversation_state",
                normalizedPrompt,
                agentConversationStateService.toStepOutput(conversationState)
        ));
        if (!carryoverContext.isBlank()) {
            steps.add(new AgentStepResponse(
                    "context_carryover",
                    "recent_conversation_context",
                    normalizedPrompt,
                    carryoverContext
            ));
        }
        steps.add(new AgentStepResponse(
                "pipeline_strategy",
                "knowledge_first_pipeline",
                intent.summary(),
                buildPipelineStepOutput(intent)
        ));

        Map<String, AgentToolExecutionService.ToolExecutionMemo> toolExecutionMemoByKey = new LinkedHashMap<>();

        emit(listener, new AgentExecutionEvent(
                "start",
                "agent",
                normalizedPrompt,
                null,
                0,
                llmClient.provider(),
                llmClient.model(),
                null
        ));
        emit(listener, new AgentExecutionEvent(
                "intent_analysis",
                "intent",
                normalizedPrompt,
                agentIntentAnalysisService.toStepOutput(intent),
                0,
                llmClient.provider(),
                llmClient.model(),
                null
        ));
        emit(listener, new AgentExecutionEvent(
                "conversation_state",
                "effective_conversation_state",
                normalizedPrompt,
                agentConversationStateService.toStepOutput(conversationState),
                0,
                llmClient.provider(),
                llmClient.model(),
                null
        ));
        if (!carryoverContext.isBlank()) {
            emit(listener, new AgentExecutionEvent(
                    "context_carryover",
                    "recent_conversation_context",
                    normalizedPrompt,
                    carryoverContext,
                    0,
                    llmClient.provider(),
                    llmClient.model(),
                    null
            ));
        }
        emit(listener, new AgentExecutionEvent(
                "pipeline_strategy",
                "knowledge_first_pipeline",
                intent.summary(),
                buildPipelineStepOutput(intent),
                0,
                llmClient.provider(),
                llmClient.model(),
                null
        ));

        return new PreparedExecution(
                userId,
                normalizedPrompt,
                intent,
                conversationState,
                instructions,
                messages,
                steps,
                toolExecutionMemoByKey
        );
    }

    private AgentChatResponse runPlanningLoop(
            PreparedExecution execution,
            AgentExecutionRegistryService.AgentExecutionHandle executionHandle,
            AgentExecutionListener listener
    ) {
        AgentExecutionHookResult beforeLoopResult = agentExecutionHookRegistryService.trigger(
                AgentExecutionHookPoint.BEFORE_AGENT_LOOP,
                hookContext(AgentExecutionHookPoint.BEFORE_AGENT_LOOP, execution, 0, null, null, listener)
        );
        if (beforeLoopResult.shouldCompleteExecution()) {
            return completeHookTerminatedExecution(execution, beforeLoopResult, listener);
        }
        for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
            checkCancelled(executionHandle);
            agentExecutionHookRegistryService.trigger(
                    AgentExecutionHookPoint.BEFORE_ROUND,
                    hookContext(AgentExecutionHookPoint.BEFORE_ROUND, execution, round, null, () -> checkCancelled(executionHandle), listener)
            );
            AgentRoundService.AgentRoundOutcome roundOutcome =
                    executePlanningRound(execution, round, executionHandle, listener);
            agentExecutionHookRegistryService.trigger(
                    AgentExecutionHookPoint.AFTER_ROUND,
                    hookContext(AgentExecutionHookPoint.AFTER_ROUND, execution, round, roundOutcome, () -> checkCancelled(executionHandle), listener)
            );
            if (roundOutcome.roundType() == AgentRoundService.RoundType.TOOL_CALLING) {
                continue;
            }
            return completeSuccessfulExecution(execution, roundOutcome.finalContent(), round, executionHandle, listener);
        }
        return completeMaxRoundExecution(execution, listener);
    }

    private AgentRoundService.AgentRoundOutcome executePlanningRound(
            PreparedExecution execution,
            int round,
            AgentExecutionRegistryService.AgentExecutionHandle executionHandle,
            AgentExecutionListener listener
    ) {
        final int currentRound = round;
        String advisorKnowledgeQuery = resolveAdvisorKnowledgeQuery(execution, round);
        if (!advisorKnowledgeQuery.isBlank()) {
            execution.steps().add(new AgentStepResponse(
                    "advisor_retrieval",
                    "spring_ai_knowledge_advisor",
                    "round=" + round,
                    "query=" + advisorKnowledgeQuery + "; topK=3"
            ));
            emit(listener, new AgentExecutionEvent(
                    "advisor_retrieval",
                    "spring_ai_knowledge_advisor",
                    advisorKnowledgeQuery,
                    "topK=3",
                    round,
                    llmClient.provider(),
                    llmClient.model(),
                    null
            ));
        }
        AgentRoundService.AgentRoundOutcome outcome = agentRoundService.executeRound(
                execution.userId(),
                execution.normalizedPrompt(),
                execution.intent(),
                execution.conversationState(),
                advisorKnowledgeQuery,
                execution.instructions(),
                execution.messages(),
                execution.steps(),
                round,
                () -> checkCancelled(executionHandle),
                delta -> emit(listener, new AgentExecutionEvent(
                        "answer_delta",
                        "assistant",
                        null,
                        delta,
                        currentRound,
                        llmClient.provider(),
                        llmClient.model(),
                        null
                )),
                new AgentRoundService.ToolEventListener() {
                    @Override
                    public void onToolCall(AssistantMessage.ToolCall toolCall, int currentRoundValue) {
                        emit(listener, new AgentExecutionEvent(
                                "tool_call",
                                toolCall.name(),
                                toolCall.arguments(),
                                null,
                                currentRoundValue,
                                llmClient.provider(),
                                llmClient.model(),
                                null
                        ));
                    }

                    @Override
                    public void onToolResult(
                            AssistantMessage.ToolCall toolCall,
                            AgentToolExecutionService.AgentToolExecutionOutcome outcome,
                            int currentRoundValue
                    ) {
                        emit(listener, new AgentExecutionEvent(
                                "tool_result",
                                toolCall.name(),
                                toolCall.arguments(),
                                outcome.output(),
                                currentRoundValue,
                                llmClient.provider(),
                                llmClient.model(),
                                outcome.code()
                        ));
                    }
                },
                execution.toolExecutionMemoByKey()
        );
        execution.steps().add(new AgentStepResponse(
                "round_type",
                outcome.roundType().name().toLowerCase(),
                "round=" + round,
                buildRoundTypeOutput(outcome)
        ));
        emit(listener, new AgentExecutionEvent(
                "round_type",
                outcome.roundType().name().toLowerCase(),
                "round=" + round,
                buildRoundTypeOutput(outcome),
                round,
                llmClient.provider(),
                llmClient.model(),
                null
        ));
        return outcome;
    }

    private String resolveAdvisorKnowledgeQuery(PreparedExecution execution, int round) {
        if (execution == null || round != 1) {
            return "";
        }
        AgentIntentAnalysisService.AgentIntent intent = execution.intent();
        if (!shouldPrefetchKnowledge(intent, execution.normalizedPrompt())) {
            return "";
        }
        return buildKnowledgePrefetchQuery(intent, execution.normalizedPrompt());
    }

    private String buildRoundTypeOutput(AgentRoundService.AgentRoundOutcome outcome) {
        if (outcome == null || outcome.roundType() == null) {
            return "unknown";
        }
        if (outcome.roundType() == AgentRoundService.RoundType.TOOL_CALLING) {
            int toolCount = outcome.toolCalls() == null ? 0 : outcome.toolCalls().size();
            return "tool_calling: model requested " + toolCount + " tool call(s)";
        }
        String preview = safeText(outcome.finalContent(), "");
        if (preview.length() > 80) {
            preview = preview.substring(0, 80) + "...";
        }
        return preview.isBlank()
                ? "final_text: model returned direct text answer"
                : "final_text: " + preview;
    }

    private AgentChatResponse completeSuccessfulExecution(
            PreparedExecution execution,
            String content,
            int round,
            AgentExecutionRegistryService.AgentExecutionHandle executionHandle,
            AgentExecutionListener listener
    ) {
        String answer = normalizeAssistantAnswer(content, execution.intent(), execution.steps());
        AgentExecutionHookContext finalAnswerContext = hookContext(
                AgentExecutionHookPoint.BEFORE_FINAL_ANSWER,
                execution,
                round,
                null,
                () -> checkCancelled(executionHandle),
                listener
        ).withFinalAnswer(answer);
        agentExecutionHookRegistryService.trigger(AgentExecutionHookPoint.BEFORE_FINAL_ANSWER, finalAnswerContext);
        answer = safeText(finalAnswerContext.finalAnswer(), answer);
        checkCancelled(executionHandle);
        if (!answer.isBlank()) {
            execution.steps().add(new AgentStepResponse("assistant", "final_answer", null, answer));
            emit(listener, new AgentExecutionEvent(
                    "final_answer",
                    "assistant",
                    null,
                    answer,
                    round,
                    llmClient.provider(),
                    llmClient.model(),
                    null
            ));
        }

        AgentChatResponse result = new AgentChatResponse(
                answer,
                execution.steps(),
                round,
                llmClient.provider(),
                llmClient.model()
        );
        emit(listener, new AgentExecutionEvent(
                "complete",
                "agent",
                null,
                answer,
                round,
                llmClient.provider(),
                llmClient.model(),
                null
        ));
        agentExecutionHookRegistryService.trigger(
                AgentExecutionHookPoint.AFTER_AGENT_LOOP,
                hookContext(AgentExecutionHookPoint.AFTER_AGENT_LOOP, execution, round, null, () -> checkCancelled(executionHandle), listener)
                        .withAttribute(ATTR_REMEMBER_SHORT_TERM_ONLY, false)
                        .withAttribute(ATTR_COMPLETION_CODE, null)
                        .withFinalAnswer(answer)
        );
        return result;
    }

    private AgentChatResponse completeMaxRoundExecution(PreparedExecution execution, AgentExecutionListener listener) {
        String fallback = agentAnswerFormatterService.formatFinalAnswer(
                "我已经完成了多轮工具检索，但这次信息仍然不够稳定。你可以再补充城市、偏好或时间段，我会继续细化路线。",
                execution.intent(),
                execution.steps()
        );
        AgentExecutionHookContext finalAnswerContext = hookContext(
                AgentExecutionHookPoint.BEFORE_FINAL_ANSWER,
                execution,
                MAX_TOOL_ROUNDS,
                null,
                null,
                listener
        ).withFinalAnswer(fallback);
        agentExecutionHookRegistryService.trigger(AgentExecutionHookPoint.BEFORE_FINAL_ANSWER, finalAnswerContext);
        fallback = safeText(finalAnswerContext.finalAnswer(), fallback);
        execution.steps().add(new AgentStepResponse("assistant", "max_round_guard", null, fallback));
        emit(listener, new AgentExecutionEvent(
                "complete",
                "agent",
                null,
                fallback,
                MAX_TOOL_ROUNDS,
                llmClient.provider(),
                llmClient.model(),
                null
        ));
        agentExecutionHookRegistryService.trigger(
                AgentExecutionHookPoint.AFTER_AGENT_LOOP,
                hookContext(AgentExecutionHookPoint.AFTER_AGENT_LOOP, execution, MAX_TOOL_ROUNDS, null, null, listener)
                        .withAttribute(ATTR_REMEMBER_SHORT_TERM_ONLY, false)
                        .withAttribute(ATTR_COMPLETION_CODE, "max_round_guard")
                        .withFinalAnswer(fallback)
        );
        return new AgentChatResponse(
                fallback,
                execution.steps(),
                MAX_TOOL_ROUNDS,
                llmClient.provider(),
                llmClient.model()
        );
    }

    private AgentChatResponse completeHookTerminatedExecution(
            PreparedExecution execution,
            AgentExecutionHookResult hookResult,
            AgentExecutionListener listener
    ) {
        String answer = safeText(hookResult == null ? null : hookResult.finalAnswer(), "");
        String completionCode = safeText(hookResult == null ? null : hookResult.code(), null);
        if (hookResult != null && hookResult.stepType() != null && hookResult.stepName() != null) {
            execution.steps().add(new AgentStepResponse(
                    hookResult.stepType(),
                    hookResult.stepName(),
                    hookResult.stepInput(),
                    answer
            ));
        }
        emit(listener, new AgentExecutionEvent(
                "final_answer",
                "assistant",
                hookResult == null ? null : hookResult.stepInput(),
                answer,
                0,
                llmClient.provider(),
                llmClient.model(),
                completionCode
        ));
        emit(listener, new AgentExecutionEvent(
                "complete",
                "agent",
                null,
                answer,
                0,
                llmClient.provider(),
                llmClient.model(),
                completionCode
        ));
        agentExecutionHookRegistryService.trigger(
                AgentExecutionHookPoint.AFTER_AGENT_LOOP,
                hookContext(AgentExecutionHookPoint.AFTER_AGENT_LOOP, execution, 0, null, null, listener)
                        .withAttribute(
                                ATTR_REMEMBER_SHORT_TERM_ONLY,
                                hookResult != null && hookResult.rememberShortTermOnly()
                        )
                        .withAttribute(ATTR_COMPLETION_CODE, completionCode)
                        .withFinalAnswer(answer)
        );
        return new AgentChatResponse(
                answer,
                execution.steps(),
                0,
                llmClient.provider(),
                llmClient.model()
        );
    }

    private String normalizeAssistantAnswer(
            String content,
            AgentIntentAnalysisService.AgentIntent intent,
            List<AgentStepResponse> steps
    ) {
        String normalized = safeText(content, "");
        if (!normalized.isBlank()) {
            return agentAnswerFormatterService.formatFinalAnswer(normalized, intent, steps);
        }
        return agentAnswerFormatterService.formatFinalAnswer(
                "我已经整理出一版基础漫步建议，但这轮没有拿到足够完整的模型文本输出。你可以继续追问想去的城市、时间段或偏好。",
                intent,
                steps
        );
    }

    private String buildIntentSpecificPipelineGuide(AgentIntentAnalysisService.AgentIntent intent) {
        if (intent == null) {
            return "";
        }

        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add("Pipeline focus for this request:");
        if (intent.needsKnowledgeReference()) {
            lines.add("- Real references are important. Search the knowledge base before giving concrete recommendations.");
        }
        if (intent.useCurrentLocation()) {
            lines.add("- The user cares about the current location. After the knowledge step, nearby_pois is preferred over a broad city-wide search.");
        }
        if (intent.needsPoiSearch() || intent.needsRoutePlanning()) {
            lines.add("- Concrete route planning is needed. Use map tools only after you have enough context from existing references or user constraints.");
        }
        if (intent.needsThemeGeneration() && !intent.needsRoutePlanning()) {
            lines.add("- This can stay at the theme or direction level. Do not force a heavy route search unless the user asks for exact stops.");
        }
        if (!intent.missingSlots().isEmpty()) {
            lines.add("- Missing slots remain: " + String.join(", ", intent.missingSlots()) + ".");
            lines.add("- If tools still cannot fill those gaps, provide conservative suggestions and briefly ask for the most important missing detail.");
        }
        if (lines.size() == 2) {
            lines.add("- Use the default knowledge-first pipeline and keep tool usage economical.");
        }
        return "\n" + String.join("\n", lines);
    }

    private String buildPipelineStepOutput(AgentIntentAnalysisService.AgentIntent intent) {
        List<String> steps = new ArrayList<>();
        steps.add("intent -> prompt assembly");
        if (intent != null && intent.needsThemeGeneration() && !intent.needsRoutePlanning()) {
            steps.add("theme direction first");
        } else {
            steps.add("deterministic knowledge prefetch");
            steps.add("deterministic poi prefetch if needed");
            steps.add("knowledge-first retrieval");
        }
        if (intent != null && intent.useCurrentLocation()) {
            steps.add("nearby poi supplement if needed");
        } else if (intent != null && (intent.needsPoiSearch() || intent.needsRoutePlanning())) {
            steps.add("poi supplement if needed");
        }
        steps.add("route synthesis");
        steps.add("final answer normalization");
        return String.join(" -> ", steps);
    }


    private boolean shouldPrefetchKnowledge(AgentIntentAnalysisService.AgentIntent intent, String prompt) {
        if (intent == null) {
            return false;
        }
        if (safeText(prompt, "").isBlank()) {
            return false;
        }
        if (intent.needsThemeGeneration() && !intent.needsRoutePlanning() && !intent.needsKnowledgeReference()) {
            return false;
        }
        return intent.needsKnowledgeReference() || intent.needsRoutePlanning();
    }

    private String buildKnowledgePrefetchQuery(AgentIntentAnalysisService.AgentIntent intent, String prompt) {
        if (intent == null) {
            return safeText(prompt, "");
        }
        List<String> parts = new ArrayList<>();
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
        if (intent.needsRoutePlanning()) {
            parts.add("city walk 路线");
        } else if (intent.needsKnowledgeReference()) {
            parts.add("city walk 参考");
        }
        String query = String.join(" ", parts).trim();
        return query.isBlank() ? safeText(prompt, "") : query;
    }


    private String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private void emit(AgentExecutionListener listener, AgentExecutionEvent event) {
        if (listener == null || event == null) {
            return;
        }
        listener.onEvent(event);
    }

    private void checkCancelled(AgentExecutionRegistryService.AgentExecutionHandle executionHandle) {
        if (executionHandle == null) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new AgentExecutionCancelledException("agent_execution_cancelled");
            }
            return;
        }
        executionHandle.checkCancelled();
    }

    private AgentExecutionHookContext hookContext(
            AgentExecutionHookPoint point,
            PreparedExecution execution,
            int round,
            AgentRoundService.AgentRoundOutcome roundOutcome,
            Runnable cancellationCheck,
            AgentExecutionListener listener
    ) {
        AgentExecutionHookContext context = new AgentExecutionHookContext(
                point,
                execution == null ? null : execution.userId(),
                execution == null ? "" : execution.normalizedPrompt(),
                execution == null ? null : execution.intent(),
                execution == null ? null : execution.conversationState(),
                execution == null ? "" : execution.instructions(),
                execution == null ? List.of() : execution.messages(),
                execution == null ? List.of() : execution.steps(),
                execution == null ? Map.of() : execution.toolExecutionMemoByKey()
        ).withRound(round)
                .withRoundOutcome(roundOutcome)
                .withExecutionListener(listener)
                .withProvider(llmClient.provider())
                .withModel(llmClient.model());
        if (cancellationCheck != null) {
            context.withCancellationCheck(cancellationCheck);
        }
        return context;
    }

    private record PreparedExecution(
            Long userId,
            String normalizedPrompt,
            AgentIntentAnalysisService.AgentIntent intent,
            AgentConversationStateService.ResolvedConversationState conversationState,
            String instructions,
            List<LlmMessage> messages,
            List<AgentStepResponse> steps,
            Map<String, AgentToolExecutionService.ToolExecutionMemo> toolExecutionMemoByKey
    ) {
    }

}
