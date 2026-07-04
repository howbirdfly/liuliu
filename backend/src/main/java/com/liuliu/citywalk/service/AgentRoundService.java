package com.liuliu.citywalk.service;

import com.liuliu.citywalk.model.dto.response.AgentStepResponse;
import com.liuliu.citywalk.service.agent.LlmMessage;
import com.liuliu.citywalk.service.agent.LlmResponse;
import com.liuliu.citywalk.service.agent.SpringAiLlmClient;
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

    public AgentRoundService(
            SpringAiLlmClient llmClient,
            AgentToolExecutionService agentToolExecutionService,
            AgentToolResultSlicerService agentToolResultSlicerService
    ) {
        this.llmClient = llmClient;
        this.agentToolExecutionService = agentToolExecutionService;
        this.agentToolResultSlicerService = agentToolResultSlicerService;
    }

    public AgentRoundOutcome executeRound(
            Long userId,
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

        if (response.hasToolCalls()) {
            List<AssistantMessage.ToolCall> toolCalls = response.toolCalls();
            messages.add(LlmMessage.assistant(response.content(), toolCalls));
            for (AssistantMessage.ToolCall toolCall : toolCalls) {
                cancellationCheck.run();
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
}
