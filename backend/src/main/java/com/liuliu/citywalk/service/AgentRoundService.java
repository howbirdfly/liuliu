package com.liuliu.citywalk.service;

import com.liuliu.citywalk.model.dto.response.AgentStepResponse;
import com.liuliu.citywalk.service.agent.LlmMessage;
import com.liuliu.citywalk.service.agent.LlmResponse;
import com.liuliu.citywalk.service.agent.SpringAiLlmClient;
import com.liuliu.citywalk.service.agent.hook.AgentExecutionHookContext;
import com.liuliu.citywalk.service.agent.hook.AgentExecutionHookPoint;
import com.liuliu.citywalk.service.agent.hook.AgentExecutionHookRegistryService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AgentRoundService {

    private final SpringAiLlmClient llmClient;
    private final AgentToolExecutionService agentToolExecutionService;
    private final AgentToolResultSlicerService agentToolResultSlicerService;
    private final AgentExecutionHookRegistryService agentExecutionHookRegistryService;

    public AgentRoundService(
            SpringAiLlmClient llmClient,
            AgentToolExecutionService agentToolExecutionService,
            AgentToolResultSlicerService agentToolResultSlicerService,
            AgentExecutionHookRegistryService agentExecutionHookRegistryService
    ) {
        this.llmClient = llmClient;
        this.agentToolExecutionService = agentToolExecutionService;
        this.agentToolResultSlicerService = agentToolResultSlicerService;
        this.agentExecutionHookRegistryService = agentExecutionHookRegistryService;
    }

    public AgentRoundOutcome executeRound(
            Long userId,
            String normalizedPrompt,
            AgentIntentAnalysisService.AgentIntent intent,
            AgentConversationStateService.ResolvedConversationState conversationState,
            String knowledgeQuery,
            String instructions,
            List<LlmMessage> messages,
            List<AgentStepResponse> steps,
            int round,
            Runnable cancellationCheck,
            DeltaListener deltaListener,
            ToolEventListener toolEventListener,
            Map<String, AgentToolExecutionService.ToolExecutionMemo> toolExecutionMemoByKey
    ) {
        List<ToolCallback> toolCallbacks = agentToolExecutionService.toolCallbacks();
        AgentExecutionHookContext beforeLlmContext = hookContext(
                AgentExecutionHookPoint.BEFORE_LLM_CALL,
                userId,
                normalizedPrompt,
                intent,
                conversationState,
                knowledgeQuery,
                instructions,
                messages,
                steps,
                round,
                cancellationCheck,
                toolExecutionMemoByKey
        );
        agentExecutionHookRegistryService.trigger(AgentExecutionHookPoint.BEFORE_LLM_CALL, beforeLlmContext);

        LlmResponse response = llmClient.createStreamingResponse(
                instructions,
                messages,
                toolCallbacks,
                0.2,
                new SpringAiLlmClient.AgentCallContext(
                        userId == null || userId <= 0 ? "" : String.valueOf(userId),
                        knowledgeQuery == null ? "" : knowledgeQuery.trim(),
                        3
                ),
                delta -> {
                    cancellationCheck.run();
                    deltaListener.onDelta(delta);
                }
        );
        cancellationCheck.run();
        agentExecutionHookRegistryService.trigger(
                AgentExecutionHookPoint.AFTER_LLM_RESPONSE,
                hookContext(
                        AgentExecutionHookPoint.AFTER_LLM_RESPONSE,
                        userId,
                        normalizedPrompt,
                        intent,
                        conversationState,
                        knowledgeQuery,
                        instructions,
                        messages,
                        steps,
                        round,
                        cancellationCheck,
                        toolExecutionMemoByKey
                ).withLlmResponse(response)
        );

        if (response.hasToolCalls()) {
            List<AssistantMessage.ToolCall> toolCalls = response.toolCalls();
            messages.add(LlmMessage.assistant(response.content(), toolCalls));
            for (AssistantMessage.ToolCall toolCall : toolCalls) {
                cancellationCheck.run();
                agentExecutionHookRegistryService.trigger(
                        AgentExecutionHookPoint.BEFORE_TOOL_CALL,
                        hookContext(
                                AgentExecutionHookPoint.BEFORE_TOOL_CALL,
                                userId,
                                normalizedPrompt,
                                intent,
                                conversationState,
                                knowledgeQuery,
                                instructions,
                                messages,
                                steps,
                                round,
                                cancellationCheck,
                                toolExecutionMemoByKey
                        ).withToolCall(toolCall)
                );
                toolEventListener.onToolCall(toolCall, round);
                AgentToolExecutionService.AgentToolExecutionOutcome outcome = agentToolExecutionService.execute(
                        toolCall,
                        cancellationCheck,
                        toolExecutionMemoByKey
                );
                steps.add(new AgentStepResponse("tool_call", toolCall.name(), toolCall.arguments(), outcome.output()));
                toolEventListener.onToolResult(toolCall, outcome, round);
                messages.add(LlmMessage.tool(
                        toolCall.id(),
                        toolCall.name(),
                        agentToolResultSlicerService.sliceForModel(toolCall.name(), outcome.output())
                ));
                agentExecutionHookRegistryService.trigger(
                        AgentExecutionHookPoint.AFTER_TOOL_RESULT,
                        hookContext(
                                AgentExecutionHookPoint.AFTER_TOOL_RESULT,
                                userId,
                                normalizedPrompt,
                                intent,
                                conversationState,
                                knowledgeQuery,
                                instructions,
                                messages,
                                steps,
                                round,
                                cancellationCheck,
                                toolExecutionMemoByKey
                        ).withToolCall(toolCall).withToolOutcome(outcome)
                );
            }
            return AgentRoundOutcome.toolCalling(toolCalls);
        }

        return AgentRoundOutcome.finalText(response.content());
    }

    public interface DeltaListener {
        void onDelta(String delta);
    }

    public interface ToolEventListener {
        void onToolCall(AssistantMessage.ToolCall toolCall, int round);

        void onToolResult(AssistantMessage.ToolCall toolCall, AgentToolExecutionService.AgentToolExecutionOutcome outcome, int round);
    }

    public record AgentRoundOutcome(
            RoundType roundType,
            String finalContent,
            List<AssistantMessage.ToolCall> toolCalls
    ) {
        public boolean continueToNextRound() {
            return roundType == RoundType.TOOL_CALLING;
        }

        public static AgentRoundOutcome toolCalling(List<AssistantMessage.ToolCall> toolCalls) {
            return new AgentRoundOutcome(
                    RoundType.TOOL_CALLING,
                    null,
                    toolCalls == null ? List.of() : List.copyOf(toolCalls)
            );
        }

        public static AgentRoundOutcome finalText(String finalContent) {
            return new AgentRoundOutcome(RoundType.FINAL_TEXT, finalContent, List.of());
        }
    }

    public enum RoundType {
        TOOL_CALLING,
        FINAL_TEXT
    }

    private AgentExecutionHookContext hookContext(
            AgentExecutionHookPoint point,
            Long userId,
            String normalizedPrompt,
            AgentIntentAnalysisService.AgentIntent intent,
            AgentConversationStateService.ResolvedConversationState conversationState,
            String knowledgeQuery,
            String instructions,
            List<LlmMessage> messages,
            List<AgentStepResponse> steps,
            int round,
            Runnable cancellationCheck,
            Map<String, AgentToolExecutionService.ToolExecutionMemo> toolExecutionMemoByKey
    ) {
        return new AgentExecutionHookContext(
                point,
                userId,
                normalizedPrompt,
                intent,
                conversationState,
                instructions,
                messages,
                steps,
                toolExecutionMemoByKey
        ).withRound(round)
                .withKnowledgeQuery(knowledgeQuery)
                .withCancellationCheck(cancellationCheck);
    }
}
