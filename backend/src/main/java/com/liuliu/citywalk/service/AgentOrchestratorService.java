package com.liuliu.citywalk.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.model.dto.response.AgentChatResponse;
import com.liuliu.citywalk.model.dto.response.AgentStepResponse;
import com.liuliu.citywalk.service.agent.AgentExecutionCancelledException;
import com.liuliu.citywalk.service.agent.AgentTool;
import com.liuliu.citywalk.service.agent.LlmClient;
import com.liuliu.citywalk.service.agent.LlmMessage;
import com.liuliu.citywalk.service.agent.LlmRequest;
import com.liuliu.citywalk.service.agent.LlmResponse;
import com.liuliu.citywalk.service.agent.LlmToolCall;
import com.liuliu.citywalk.service.agent.LlmToolDefinition;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentOrchestratorService {

    private static final int MAX_TOOL_ROUNDS = 6;

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
    private final AgentMemoryService agentMemoryService;
    private final AgentLongTermMemoryService agentLongTermMemoryService;
    private final ObjectMapper objectMapper;
    private final Map<String, AgentTool> toolsByName;
    private final List<LlmToolDefinition> toolDefinitions;

    public AgentOrchestratorService(
            LlmClient llmClient,
            AgentMemoryService agentMemoryService,
            AgentLongTermMemoryService agentLongTermMemoryService,
            ObjectMapper objectMapper,
            List<AgentTool> agentTools
    ) {
        this.llmClient = llmClient;
        this.agentMemoryService = agentMemoryService;
        this.agentLongTermMemoryService = agentLongTermMemoryService;
        this.objectMapper = objectMapper;
        this.toolsByName = new LinkedHashMap<>();
        this.toolDefinitions = new ArrayList<>();
        for (AgentTool tool : agentTools) {
            this.toolsByName.put(tool.name(), tool);
            this.toolDefinitions.add(tool.toDefinition());
        }
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
        agentMemoryService.clearConversation(userId);
    }

    private AgentChatResponse execute(
            Long userId,
            String prompt,
            AgentExecutionRegistryService.AgentExecutionHandle executionHandle,
            AgentExecutionListener listener
    ) {
        String normalizedPrompt = prompt == null ? "" : prompt.trim();
        checkCancelled(executionHandle);

        List<LlmMessage> messages = new ArrayList<>();
        messages.addAll(agentMemoryService.loadConversation(userId));
        messages.add(LlmMessage.user(normalizedPrompt));
        emit(listener, new AgentExecutionEvent("start", "agent", normalizedPrompt, null, 0, llmClient.provider(), llmClient.model(), null));
        String instructions = buildInstructions(userId);

        List<AgentStepResponse> steps = new ArrayList<>();
        for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
            final int currentRound = round;
            checkCancelled(executionHandle);

            LlmResponse response = llmClient.createStreamingResponse(
                    new LlmRequest(
                            instructions,
                            messages,
                            toolDefinitions,
                            0.2
                    ),
                    delta -> {
                        checkCancelled(executionHandle);
                        emit(listener, new AgentExecutionEvent(
                                "answer_delta",
                                "assistant",
                                null,
                                delta,
                                currentRound,
                                llmClient.provider(),
                                llmClient.model(),
                                null
                        ));
                    }
            );
            checkCancelled(executionHandle);

            if (response.hasToolCalls()) {
                messages.add(LlmMessage.assistant(response.content(), response.toolCalls()));
                for (LlmToolCall toolCall : response.toolCalls()) {
                    checkCancelled(executionHandle);
                    emit(listener, new AgentExecutionEvent(
                            "tool_call",
                            toolCall.name(),
                            toolCall.argumentsJson(),
                            null,
                            round,
                            llmClient.provider(),
                            llmClient.model(),
                            null
                    ));
                    String toolOutput = executeTool(toolCall, steps, round, executionHandle, listener);
                    messages.add(LlmMessage.tool(toolCall.id(), toolCall.name(), toolOutput));
                }
                continue;
            }

            String answer = normalizeAssistantAnswer(response.content());
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
            rememberConversation(userId, normalizedPrompt, answer);
            emit(listener, new AgentExecutionEvent("complete", "agent", null, answer, round, llmClient.provider(), llmClient.model(), null));
            return result;
        }

        String fallback = "我已经完成了多轮工具检索，但这次信息仍然不够稳定。你可以再补充城市、偏好或时间段，我会继续细化路线。";
        steps.add(new AgentStepResponse("assistant", "max_round_guard", null, fallback));
        rememberConversation(userId, normalizedPrompt, fallback);
        emit(listener, new AgentExecutionEvent("complete", "agent", null, fallback, MAX_TOOL_ROUNDS, llmClient.provider(), llmClient.model(), null));
        return new AgentChatResponse(
                fallback,
                steps,
                MAX_TOOL_ROUNDS,
                llmClient.provider(),
                llmClient.model()
        );
    }

    private void rememberConversation(Long userId, String userPrompt, String assistantAnswer) {
        agentMemoryService.appendTurn(userId, userPrompt, assistantAnswer);
        agentLongTermMemoryService.rememberTurn(userId, userPrompt, assistantAnswer);
    }

    private String buildInstructions(Long userId) {
        String memoryContext = agentLongTermMemoryService.buildPromptContext(userId);
        if (memoryContext.isBlank()) {
            return DEFAULT_INSTRUCTIONS;
        }
        return DEFAULT_INSTRUCTIONS + memoryContext;
    }

    private String executeTool(
            LlmToolCall toolCall,
            List<AgentStepResponse> steps,
            int round,
            AgentExecutionRegistryService.AgentExecutionHandle executionHandle,
            AgentExecutionListener listener
    ) {
        checkCancelled(executionHandle);
        AgentTool tool = toolsByName.get(toolCall.name());
        if (tool == null) {
            String output = json(Map.of(
                    "error", "tool_not_found",
                    "name", toolCall.name()
            ));
            steps.add(new AgentStepResponse("tool_call", toolCall.name(), toolCall.argumentsJson(), output));
            emit(listener, new AgentExecutionEvent("tool_result", toolCall.name(), toolCall.argumentsJson(), output, round, llmClient.provider(), llmClient.model(), "tool_not_found"));
            return output;
        }

        try {
            Map<String, Object> arguments = parseArguments(toolCall.argumentsJson());
            String output = tool.execute(arguments);
            checkCancelled(executionHandle);
            steps.add(new AgentStepResponse("tool_call", toolCall.name(), toolCall.argumentsJson(), output));
            emit(listener, new AgentExecutionEvent("tool_result", toolCall.name(), toolCall.argumentsJson(), output, round, llmClient.provider(), llmClient.model(), null));
            return output;
        } catch (AgentExecutionCancelledException error) {
            throw error;
        } catch (Exception error) {
            String output = json(Map.of(
                    "error", "tool_execution_failed",
                    "name", toolCall.name(),
                    "message", safeText(error.getMessage(), "unknown_error")
            ));
            steps.add(new AgentStepResponse("tool_call", toolCall.name(), toolCall.argumentsJson(), output));
            emit(listener, new AgentExecutionEvent("tool_result", toolCall.name(), toolCall.argumentsJson(), output, round, llmClient.provider(), llmClient.model(), "tool_execution_failed"));
            return output;
        }
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception error) {
            return Map.of();
        }
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

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            return "{\"error\":\"json_encode_failed\"}";
        }
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
