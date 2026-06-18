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
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class AgentOrchestratorService {

    private static final int MAX_TOOL_ROUNDS = 6;
    private static final String FALLBACK_GUIDE = """

            濡傛灉宸ュ叿杩斿洖 success=false锛屾垨鑰呭寘鍚?error / fallbackSuggestion 瀛楁锛岃鏄庤繖涓€姝ユ病鏈夋嬁鍒板彲闈犲伐鍏风粨鏋溿€?                杩欑鎯呭喌涓嬩笉瑕佸亣瑁呮嬁鍒颁簡鐪熷疄鏁版嵁锛岃缁撳悎宸叉湁涓婁笅鏂囥€佸叾浠栧伐鍏风粨鏋滃拰甯歌瘑缁х画缁欏嚭淇濆畧寤鸿锛屽苟鏄庣‘璇存槑鍝簺淇℃伅缂哄皯宸ュ叿鏀拺銆?                """;

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
    private final AgentToolResultCacheService agentToolResultCacheService;
    private final AgentPromptAssemblyService agentPromptAssemblyService;
    private final AgentToolExecutionService agentToolExecutionService;
    private final AgentRoundService agentRoundService;
    private final ObjectMapper objectMapper;
    private final Map<String, AgentTool> toolsByName;
    private final List<com.liuliu.citywalk.service.agent.LlmToolDefinition> toolDefinitions;

    public AgentOrchestratorService(
            LlmClient llmClient,
            AgentMemoryService agentMemoryService,
            AgentLongTermMemoryService agentLongTermMemoryService,
            AgentToolResultCacheService agentToolResultCacheService,
            AgentPromptAssemblyService agentPromptAssemblyService,
            AgentToolExecutionService agentToolExecutionService,
            AgentRoundService agentRoundService,
            ObjectMapper objectMapper,
            List<AgentTool> agentTools
    ) {
        this.llmClient = llmClient;
        this.agentMemoryService = agentMemoryService;
        this.agentLongTermMemoryService = agentLongTermMemoryService;
        this.agentToolResultCacheService = agentToolResultCacheService;
        this.agentPromptAssemblyService = agentPromptAssemblyService;
        this.agentToolExecutionService = agentToolExecutionService;
        this.agentRoundService = agentRoundService;
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

    private void rememberConversation(Long userId, String userPrompt, String assistantAnswer) {
        agentPromptAssemblyService.rememberConversation(userId, userPrompt, assistantAnswer);
    }

    private String buildInstructions(Long userId) {
        String memoryContext = agentLongTermMemoryService.buildPromptContext(userId);
        String fallbackGuide = """

                如果工具返回 success=false，或者包含 error / fallbackSuggestion 字段，说明这一步没有拿到可靠工具结果。
                这种情况下不要假装拿到了真实数据，要结合已有上下文、其他工具结果和常识继续给出保守建议，并明确说明哪些信息缺少工具支撑。
                """;
        if (memoryContext.isBlank()) {
            return DEFAULT_INSTRUCTIONS + fallbackGuide;
        }
        return DEFAULT_INSTRUCTIONS + memoryContext + fallbackGuide;
    }

    private String executeTool(
            LlmToolCall toolCall,
            List<AgentStepResponse> steps,
            int round,
            AgentExecutionRegistryService.AgentExecutionHandle executionHandle,
            AgentExecutionListener listener,
            Map<String, AgentToolExecutionService.ToolExecutionMemo> toolExecutionMemoByKey
    ) {
        AgentToolExecutionService.AgentToolExecutionOutcome outcome = agentToolExecutionService.execute(
                toolCall,
                () -> checkCancelled(executionHandle),
                toolExecutionMemoByKey
        );
        steps.add(new AgentStepResponse("tool_call", toolCall.name(), toolCall.argumentsJson(), outcome.output()));
        emit(listener, new AgentExecutionEvent(
                "tool_result",
                toolCall.name(),
                toolCall.argumentsJson(),
                outcome.output(),
                round,
                llmClient.provider(),
                llmClient.model(),
                outcome.code()
        ));
        return outcome.output();
    }

    private String buildToolFailureOutput(String toolName, String errorCode, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("error", errorCode);
        payload.put("name", toolName);
        payload.put("message", message);
        payload.put("canContinue", true);
        payload.put("fallbackSuggestion", fallbackSuggestion(toolName, errorCode));

        switch (toolName) {
            case "search_poi", "nearby_pois", "search_community_guides", "search_knowledge_base" -> payload.put("results", List.of());
            case "get_walk_detail" -> payload.put("found", false);
            default -> {
            }
        }

        return json(payload);
    }

    private String fallbackSuggestion(String toolName, String errorCode) {
        String suggestion = switch (toolName) {
            case "search_knowledge_base" ->
                    "知识库结果不可用时，继续结合地图工具、社区公开内容和已有上下文给出建议，并明确说明知识库未成功返回。";
            case "search_community_guides" ->
                    "社区攻略检索失败时，优先回退到地图搜索和通用路线建议，不要编造具体帖子内容。";
            case "search_poi", "nearby_pois" ->
                    "地图工具失败时，可以基于用户已提供的区域、历史偏好和其他工具结果给出保守建议，并说明地点准确性有限。";
            case "get_walk_detail" ->
                    "单条 Walk 详情获取失败时，不要假设帖子细节存在，继续基于已有公开信息给出概括性建议。";
            default ->
                    "工具未成功返回可靠结果时，基于已有上下文继续回答，并明确告知用户这一步缺少工具支撑。";
        };
        if ("tool_arguments_invalid".equals(errorCode)) {
            return suggestion + " 这次失败也可能是工具参数不完整或格式不正确。";
        }
        return suggestion;
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

    private void cacheToolExecution(Map<String, ToolExecutionMemo> toolExecutionMemoByKey, String invocationKey, String output) {
        if (invocationKey == null || invocationKey.isBlank()) {
            return;
        }
        toolExecutionMemoByKey.put(invocationKey, new ToolExecutionMemo(output));
    }

    private void cacheSharedToolExecution(AgentTool tool, String invocationKey, String output) {
        if (invocationKey == null || invocationKey.isBlank()) {
            return;
        }
        if (tool == null || !tool.supportsSharedResultCache() || !agentToolResultCacheService.isEnabled()) {
            return;
        }
        if (!isSharedCacheableOutput(output)) {
            return;
        }
        agentToolResultCacheService.put(invocationKey, output);
    }

    private String getSharedCachedToolOutput(AgentTool tool, String invocationKey) {
        if (invocationKey == null || invocationKey.isBlank()) {
            return null;
        }
        if (tool == null || !tool.supportsSharedResultCache() || !agentToolResultCacheService.isEnabled()) {
            return null;
        }
        return agentToolResultCacheService.get(invocationKey);
    }

    private String buildToolInvocationKey(AgentTool tool, Map<String, Object> arguments) {
        if (tool == null || !tool.supportsIdempotentReplay()) {
            return null;
        }
        try {
            return tool.name() + ":" + objectMapper.writeValueAsString(normalizeForSignature(arguments));
        } catch (Exception error) {
            return tool.name() + ":" + String.valueOf(normalizeForSignature(arguments));
        }
    }

    private Object normalizeForSignature(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                normalized.put(entry.getKey().toString(), normalizeForSignature(entry.getValue()));
            }
            return normalized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> normalized = new ArrayList<>();
            for (Object item : iterable) {
                normalized.add(normalizeForSignature(item));
            }
            return normalized;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> normalized = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                normalized.add(normalizeForSignature(java.lang.reflect.Array.get(value, index)));
            }
            return normalized;
        }
        return value;
    }

    private boolean isSharedCacheableOutput(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(output, new TypeReference<Map<String, Object>>() {
            });
            Object success = payload.get("success");
            if (success instanceof Boolean value && !value) {
                return false;
            }

            if (payload.containsKey("found")) {
                Object found = payload.get("found");
                if (found instanceof Boolean value && !value) {
                    return false;
                }
            }

            if (payload.containsKey("results")) {
                Object results = payload.get("results");
                if (results instanceof Iterable<?> iterable) {
                    return iterable.iterator().hasNext();
                }
                if (results != null && results.getClass().isArray()) {
                    return java.lang.reflect.Array.getLength(results) > 0;
                }
            }

            return true;
        } catch (Exception error) {
            return false;
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

    private record ToolExecutionMemo(
            String output
    ) {
    }
}
