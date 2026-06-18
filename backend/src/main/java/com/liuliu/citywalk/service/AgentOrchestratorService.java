package com.liuliu.citywalk.service;

import com.liuliu.citywalk.model.dto.response.AgentChatResponse;
import com.liuliu.citywalk.model.dto.response.AgentStepResponse;
import com.liuliu.citywalk.service.agent.AgentExecutionCancelledException;
import com.liuliu.citywalk.service.agent.LlmClient;
import com.liuliu.citywalk.service.agent.LlmMessage;
import com.liuliu.citywalk.service.agent.LlmToolCall;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentOrchestratorService {

    private static final int MAX_TOOL_ROUNDS = 6;
    private static final String FALLBACK_GUIDE = """

            如果工具返回 success=false，或者包含 error / fallbackSuggestion 字段，说明这一步没有拿到可靠工具结果。
            这种情况下不要假装拿到了真实数据，要结合已有上下文、其他工具结果和常识继续给出保守建议，并明确说明哪些信息缺少工具支撑。
            """;

    private static final String DEFAULT_INSTRUCTIONS = """
            你是 Liuliu City Walk 的智能规划 Agent。
            你的目标是根据用户的自然语言需求，尽量结合工具结果生成可执行的城市漫步建议。
            当你缺少地点、社区攻略或路线细节时，优先调用工具，不要凭空编造具体地点。
            如果用户在问有没有类似路线参考、别人怎么走、适合海边日落/校园散步/老街拍照的路线，
            或者明显需要参考历史公开帖子时，优先调用 search_knowledge_base 检索知识库，再结合地图工具补地点。
            如果知识库已经命中相关帖子，最终回答里要尽量吸收这些真实内容，但不要逐字照抄原文。
            如果工具返回的结果不足，也要明确告诉用户哪些内容是推测，哪些内容来自工具。
            最终回答尽量包含：适合的区域、推荐理由、可逛兴趣点、建议路线顺序，以及一两句贴心提醒。
            """;

    private final LlmClient llmClient;
    private final AgentPromptAssemblyService agentPromptAssemblyService;
    private final AgentRoundService agentRoundService;

    public AgentOrchestratorService(
            LlmClient llmClient,
            AgentPromptAssemblyService agentPromptAssemblyService,
            AgentRoundService agentRoundService
    ) {
        this.llmClient = llmClient;
        this.agentPromptAssemblyService = agentPromptAssemblyService;
        this.agentRoundService = agentRoundService;
    }

    public AgentChatResponse chat(Long userId, String prompt) {
        return execute(userId, prompt, null, null);
    }

    public AgentChatResponse stream(
            Long userId,
            String prompt,
            AgentExecutionRegistryService.AgentExecutionHandle executionHandle,
            AgentExecutionListener listener
    ) {
        return execute(userId, prompt, executionHandle, listener);
    }

    public void clearConversation(Long userId) {
        agentPromptAssemblyService.clearConversation(userId);
    }

    private AgentChatResponse execute(
            Long userId,
            String prompt,
            AgentExecutionRegistryService.AgentExecutionHandle executionHandle,
            AgentExecutionListener listener
    ) {
        String normalizedPrompt = prompt == null ? "" : prompt.trim();
        checkCancelled(executionHandle);

        List<LlmMessage> messages = agentPromptAssemblyService.buildConversationMessages(userId, normalizedPrompt);
        emit(listener, new AgentExecutionEvent("start", "agent", normalizedPrompt, null, 0, llmClient.provider(), llmClient.model(), null));
        String instructions = agentPromptAssemblyService.buildInstructions(userId, DEFAULT_INSTRUCTIONS, FALLBACK_GUIDE);

        List<AgentStepResponse> steps = new ArrayList<>();
        Map<String, AgentToolExecutionService.ToolExecutionMemo> toolExecutionMemoByKey = new LinkedHashMap<>();
        for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
            final int currentRound = round;
            checkCancelled(executionHandle);

            AgentRoundService.AgentRoundOutcome roundOutcome = agentRoundService.executeRound(
                    instructions,
                    messages,
                    steps,
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
                        public void onToolCall(LlmToolCall toolCall, int currentRoundValue) {
                            emit(listener, new AgentExecutionEvent(
                                    "tool_call",
                                    toolCall.name(),
                                    toolCall.argumentsJson(),
                                    null,
                                    currentRoundValue,
                                    llmClient.provider(),
                                    llmClient.model(),
                                    null
                            ));
                        }

                        @Override
                        public void onToolResult(LlmToolCall toolCall, AgentToolExecutionService.AgentToolExecutionOutcome outcome, int currentRoundValue) {
                            emit(listener, new AgentExecutionEvent(
                                    "tool_result",
                                    toolCall.name(),
                                    toolCall.argumentsJson(),
                                    outcome.output(),
                                    currentRoundValue,
                                    llmClient.provider(),
                                    llmClient.model(),
                                    outcome.code()
                            ));
                        }
                    },
                    toolExecutionMemoByKey
            );

            if (roundOutcome.continueToNextRound()) {
                continue;
            }

            String answer = normalizeAssistantAnswer(roundOutcome.finalContent());
            checkCancelled(executionHandle);
            if (!answer.isBlank()) {
                steps.add(new AgentStepResponse("assistant", "final_answer", null, answer));
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
                    steps,
                    round,
                    llmClient.provider(),
                    llmClient.model()
            );
            agentPromptAssemblyService.rememberConversation(userId, normalizedPrompt, answer);
            emit(listener, new AgentExecutionEvent("complete", "agent", null, answer, round, llmClient.provider(), llmClient.model(), null));
            return result;
        }

        String fallback = "我已经完成了多轮工具检索，但这次信息仍然不够稳定。你可以再补充城市、偏好或时间段，我会继续细化路线。";
        steps.add(new AgentStepResponse("assistant", "max_round_guard", null, fallback));
        agentPromptAssemblyService.rememberConversation(userId, normalizedPrompt, fallback);
        emit(listener, new AgentExecutionEvent("complete", "agent", null, fallback, MAX_TOOL_ROUNDS, llmClient.provider(), llmClient.model(), null));
        return new AgentChatResponse(
                fallback,
                steps,
                MAX_TOOL_ROUNDS,
                llmClient.provider(),
                llmClient.model()
        );
    }

    private String normalizeAssistantAnswer(String content) {
        String normalized = safeText(content, "");
        if (!normalized.isBlank()) {
            return normalized;
        }
        return "我已经整理出一版基础漫步建议，但这轮没有拿到足够完整的模型文本输出。你可以继续追问我想去的城市、时间段或偏好。";
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

    public interface AgentExecutionListener {
        void onEvent(AgentExecutionEvent event);
    }

    public record AgentExecutionEvent(
            String type,
            String name,
            String input,
            String output,
            int iteration,
            String provider,
            String model,
            String code
    ) {
    }
}
