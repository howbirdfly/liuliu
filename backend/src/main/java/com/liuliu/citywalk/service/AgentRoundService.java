package com.liuliu.citywalk.service;

import com.liuliu.citywalk.model.dto.response.AgentStepResponse;
import com.liuliu.citywalk.service.agent.LlmClient;
import com.liuliu.citywalk.service.agent.LlmMessage;
import com.liuliu.citywalk.service.agent.LlmRequest;
import com.liuliu.citywalk.service.agent.LlmResponse;
import com.liuliu.citywalk.service.agent.LlmToolCall;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AgentRoundService {

    private final LlmClient llmClient;
    private final AgentToolExecutionService agentToolExecutionService;

    public AgentRoundService(
            LlmClient llmClient,
            AgentToolExecutionService agentToolExecutionService
    ) {
        this.llmClient = llmClient;
        this.agentToolExecutionService = agentToolExecutionService;
    }

    public AgentRoundOutcome executeRound(
            String instructions,
            List<LlmMessage> messages,
            List<AgentStepResponse> steps,
            int round,
            Runnable cancellationCheck,
            DeltaListener deltaListener,
            ToolEventListener toolEventListener,
            Map<String, AgentToolExecutionService.ToolExecutionMemo> toolExecutionMemoByKey
    ) {
        LlmResponse response = llmClient.createStreamingResponse(
                new LlmRequest(
                        instructions,
                        messages,
                        agentToolExecutionService.toolDefinitions(),
                        0.2
                ),
                delta -> {
                    cancellationCheck.run();
                    deltaListener.onDelta(delta);
                }
        );
        cancellationCheck.run();

        if (response.hasToolCalls()) {
            messages.add(LlmMessage.assistant(response.content(), response.toolCalls()));
            for (LlmToolCall toolCall : response.toolCalls()) {
                cancellationCheck.run();
                toolEventListener.onToolCall(toolCall, round);
                AgentToolExecutionService.AgentToolExecutionOutcome outcome = agentToolExecutionService.execute(
                        toolCall,
                        cancellationCheck,
                        toolExecutionMemoByKey
                );
                steps.add(new AgentStepResponse("tool_call", toolCall.name(), toolCall.argumentsJson(), outcome.output()));
                toolEventListener.onToolResult(toolCall, outcome, round);
                messages.add(LlmMessage.tool(toolCall.id(), toolCall.name(), outcome.output()));
            }
            return AgentRoundOutcome.requiresAnotherRound();
        }

        return AgentRoundOutcome.completed(response.content());
    }

    public interface DeltaListener {
        void onDelta(String delta);
    }

    public interface ToolEventListener {
        void onToolCall(LlmToolCall toolCall, int round);

        void onToolResult(LlmToolCall toolCall, AgentToolExecutionService.AgentToolExecutionOutcome outcome, int round);
    }

    public record AgentRoundOutcome(
            boolean continueToNextRound,
            String finalContent
    ) {
        public static AgentRoundOutcome requiresAnotherRound() {
            return new AgentRoundOutcome(true, null);
        }

        public static AgentRoundOutcome completed(String finalContent) {
            return new AgentRoundOutcome(false, finalContent);
        }
    }
}
