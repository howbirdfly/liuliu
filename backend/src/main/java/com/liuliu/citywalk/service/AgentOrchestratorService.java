package com.liuliu.citywalk.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.model.dto.response.AgentChatResponse;
import com.liuliu.citywalk.model.dto.response.AgentStepResponse;
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

    // 限制单次请求最多循环多少轮工具调用，避免 Agent 卡在无限循环里。
    private static final int MAX_TOOL_ROUNDS = 6;
    private static final String DEFAULT_INSTRUCTIONS = """
            你是 Liuliu City Walk 的智能规划 Agent。
            你的目标是根据用户的自然语言需求，尽量结合工具结果生成可执行的城市漫步建议。
            当你缺少地点、社区攻略或路线细节时，优先调用工具，不要凭空编造具体地点。
            如果工具返回的结果不足，也要明确告诉用户哪些内容是推测，哪些内容来自工具。
            最终回答尽量包含：适合的区域、推荐理由、可逛兴趣点、建议路线顺序，以及一两句贴心提醒。
            """;

    private final LlmClient llmClient;
    private final AgentMemoryService agentMemoryService;
    private final ObjectMapper objectMapper;
    private final Map<String, AgentTool> toolsByName;
    private final List<LlmToolDefinition> toolDefinitions;

    public AgentOrchestratorService(
            LlmClient llmClient,
            AgentMemoryService agentMemoryService,
            ObjectMapper objectMapper,
            List<AgentTool> agentTools
    ) {
        this.llmClient = llmClient;
        this.agentMemoryService = agentMemoryService;
        this.objectMapper = objectMapper;
        this.toolsByName = new LinkedHashMap<>();
        this.toolDefinitions = new ArrayList<>();
        for (AgentTool tool : agentTools) {
            this.toolsByName.put(tool.name(), tool);
            this.toolDefinitions.add(tool.toDefinition());
        }
    }

    public AgentChatResponse chat(Long userId, String prompt) {
        return execute(userId, prompt, null);
    }

    public AgentChatResponse stream(Long userId, String prompt, AgentExecutionListener listener) {
        return execute(userId, prompt, listener);
    }

    public void clearConversation(Long userId) {
        agentMemoryService.clearConversation(userId);
    }

    private AgentChatResponse execute(Long userId, String prompt, AgentExecutionListener listener) {
        String normalizedPrompt = prompt == null ? "" : prompt.trim();
        List<LlmMessage> messages = new ArrayList<>();
        // 先把 Redis 里最近几轮对话取出来，再拼上当前这一轮用户输入。
        messages.addAll(agentMemoryService.loadConversation(userId));
        messages.add(LlmMessage.user(normalizedPrompt));
        emit(listener, new AgentExecutionEvent("start", "agent", normalizedPrompt, null, 0));

        List<AgentStepResponse> steps = new ArrayList<>();
        for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
            // 每一轮都会把当前对话上下文和所有可用工具一起发给模型。
            LlmResponse response = llmClient.createResponse(new LlmRequest(
                    DEFAULT_INSTRUCTIONS,
                    messages,
                    toolDefinitions,
                    0.2
            ));

            if (response.hasToolCalls()) {
                // 先把“模型刚刚请求过哪些工具”记进上下文，下一轮模型才能接着往下推理。
                messages.add(LlmMessage.assistant(response.content(), response.toolCalls()));
                for (LlmToolCall toolCall : response.toolCalls()) {
                    emit(listener, new AgentExecutionEvent(
                            "tool_call",
                            toolCall.name(),
                            toolCall.argumentsJson(),
                            null,
                            round
                    ));
                    String toolOutput = executeTool(toolCall, steps, round, listener);
                    messages.add(LlmMessage.tool(toolCall.id(), toolCall.name(), toolOutput));
                }
                continue;
            }

            // 没有继续发起工具调用，说明模型已经准备直接给最终答案了。
            String answer = normalizeAssistantAnswer(response.content());
            if (!answer.isBlank()) {
                steps.add(new AgentStepResponse(
                        "assistant",
                        "final_answer",
                        null,
                        answer
                ));
                emit(listener, new AgentExecutionEvent(
                        "final_answer",
                        "assistant",
                        null,
                        answer,
                        round
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
            emit(listener, new AgentExecutionEvent("complete", "agent", null, answer, round));
            return result;
        }

        String fallback = "我已经完成了多轮工具检索，但这次信息仍然不够稳定。你可以再补充城市、偏好或时间段，我会继续细化路线。";
        steps.add(new AgentStepResponse("assistant", "max_round_guard", null, fallback));
        rememberConversation(userId, normalizedPrompt, fallback);
        emit(listener, new AgentExecutionEvent("complete", "agent", null, fallback, MAX_TOOL_ROUNDS));
        return new AgentChatResponse(
                fallback,
                steps,
                MAX_TOOL_ROUNDS,
                llmClient.provider(),
                llmClient.model()
        );
    }

    private void rememberConversation(Long userId, String userPrompt, String assistantAnswer) {
        // Redis 里只记最终的问答轮次，不把工具过程塞进去，避免上下文越来越臃肿。
        agentMemoryService.appendTurn(userId, userPrompt, assistantAnswer);
    }

    private String executeTool(
            LlmToolCall toolCall,
            List<AgentStepResponse> steps,
            int round,
            AgentExecutionListener listener
    ) {
        AgentTool tool = toolsByName.get(toolCall.name());
        if (tool == null) {
            // 工具不存在时返回结构化错误，而不是让整个 Agent 直接中断。
            String output = json(Map.of(
                    "error", "tool_not_found",
                    "name", toolCall.name()
            ));
            steps.add(new AgentStepResponse("tool_call", toolCall.name(), toolCall.argumentsJson(), output));
            emit(listener, new AgentExecutionEvent("tool_result", toolCall.name(), toolCall.argumentsJson(), output, round));
            return output;
        }

        try {
            Map<String, Object> arguments = parseArguments(toolCall.argumentsJson());
            String output = tool.execute(arguments);
            // 工具结果要重新塞回对话里，模型下一轮才能基于这些真实数据继续推理。
            steps.add(new AgentStepResponse("tool_call", toolCall.name(), toolCall.argumentsJson(), output));
            emit(listener, new AgentExecutionEvent("tool_result", toolCall.name(), toolCall.argumentsJson(), output, round));
            return output;
        } catch (Exception error) {
            String output = json(Map.of(
                    "error", "tool_execution_failed",
                    "name", toolCall.name(),
                    "message", safeText(error.getMessage(), "unknown_error")
            ));
            steps.add(new AgentStepResponse("tool_call", toolCall.name(), toolCall.argumentsJson(), output));
            emit(listener, new AgentExecutionEvent("tool_result", toolCall.name(), toolCall.argumentsJson(), output, round));
            return output;
        }
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            // 模型传回来的工具参数本质上是一段 JSON 文本，这里统一解析一次。
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

    public interface AgentExecutionListener {
        void onEvent(AgentExecutionEvent event);
    }

    public record AgentExecutionEvent(
            String type,
            String name,
            String input,
            String output,
            int iteration
    ) {
    }
}
