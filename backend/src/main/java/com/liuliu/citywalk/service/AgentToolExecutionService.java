package com.liuliu.citywalk.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.service.agent.AgentExecutionCancelledException;
import com.liuliu.citywalk.service.agent.AgentTool;
import com.liuliu.citywalk.service.agent.LlmToolCall;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class AgentToolExecutionService {

    private final ObjectMapper objectMapper;
    private final AgentToolResultCacheService agentToolResultCacheService;
    private final AgentToolFailurePayloadService agentToolFailurePayloadService;
    private final Map<String, AgentTool> toolsByName;
    private final List<ToolCallback> toolCallbacks;

    public AgentToolExecutionService(
            ObjectMapper objectMapper,
            AgentToolResultCacheService agentToolResultCacheService,
            AgentToolFailurePayloadService agentToolFailurePayloadService,
            List<AgentTool> agentTools
    ) {
        this.objectMapper = objectMapper;
        this.agentToolResultCacheService = agentToolResultCacheService;
        this.agentToolFailurePayloadService = agentToolFailurePayloadService;
        this.toolsByName = new LinkedHashMap<>();
        this.toolCallbacks = new ArrayList<>();
        for (AgentTool tool : agentTools) {
            this.toolsByName.put(tool.name(), tool);
            this.toolCallbacks.add(tool.toToolCallback());
        }
    }

    public List<ToolCallback> toolCallbacks() {
        return toolCallbacks;
    }

    public boolean hasTool(String toolName) {
        return toolName != null && toolsByName.containsKey(toolName);
    }

    public AgentToolExecutionOutcome execute(
            LlmToolCall toolCall,
            Runnable cancellationCheck,
            Map<String, ToolExecutionMemo> toolExecutionMemoByKey
    ) {
        cancellationCheck.run();
        return executeInternal(
                toolCall == null ? null : toolCall.name(),
                toolCall == null ? null : toolCall.argumentsJson(),
                cancellationCheck,
                toolExecutionMemoByKey
        );
    }

    public AgentToolExecutionOutcome executePrefetched(
            String toolName,
            String argumentsJson,
            Runnable cancellationCheck,
            Map<String, ToolExecutionMemo> toolExecutionMemoByKey
    ) {
        cancellationCheck.run();
        return executeInternal(toolName, argumentsJson, cancellationCheck, toolExecutionMemoByKey);
    }

    private AgentToolExecutionOutcome executeInternal(
            String toolName,
            String argumentsJson,
            Runnable cancellationCheck,
            Map<String, ToolExecutionMemo> toolExecutionMemoByKey
    ) {
        AgentTool tool = toolsByName.get(toolName);
        if (tool == null) {
            return failure(toolName, "tool_not_found", "tool_not_found", toolExecutionMemoByKey, null);
        }

        try {
            Map<String, Object> arguments = parseArguments(argumentsJson);
            String invocationKey = buildToolInvocationKey(tool, arguments);
            if (invocationKey != null) {
                ToolExecutionMemo memo = toolExecutionMemoByKey.get(invocationKey);
                if (memo != null) {
                    return new AgentToolExecutionOutcome(memo.output(), "tool_result_reused");
                }

                String sharedCachedOutput = getSharedCachedToolOutput(tool, invocationKey);
                if (sharedCachedOutput != null) {
                    cacheToolExecution(toolExecutionMemoByKey, invocationKey, sharedCachedOutput);
                    return new AgentToolExecutionOutcome(sharedCachedOutput, "tool_result_shared_cache_hit");
                }
            }

            String output = tool.execute(arguments);
            cancellationCheck.run();
            cacheToolExecution(toolExecutionMemoByKey, invocationKey, output);
            cacheSharedToolExecution(tool, invocationKey, output);
            return new AgentToolExecutionOutcome(output, null);
        } catch (AgentExecutionCancelledException error) {
            throw error;
        } catch (Exception error) {
            String errorCode = error instanceof IllegalArgumentException ? "tool_arguments_invalid" : "tool_execution_failed";
            String message = safeText(error.getMessage(), "unknown_error");
            String failureOutput;
            try {
                Map<String, Object> arguments = parseArguments(argumentsJson);
                String invocationKey = buildToolInvocationKey(tool, arguments);
                failureOutput = agentToolFailurePayloadService.build(toolName, errorCode, message);
                cacheToolExecution(toolExecutionMemoByKey, invocationKey, failureOutput);
            } catch (Exception ignored) {
                failureOutput = agentToolFailurePayloadService.build(toolName, errorCode, message);
            }
            return new AgentToolExecutionOutcome(failureOutput, errorCode);
        }
    }

    private AgentToolExecutionOutcome failure(
            String toolName,
            String errorCode,
            String message,
            Map<String, ToolExecutionMemo> toolExecutionMemoByKey,
            String invocationKey
    ) {
        String output = agentToolFailurePayloadService.build(toolName, errorCode, message);
        cacheToolExecution(toolExecutionMemoByKey, invocationKey, output);
        return new AgentToolExecutionOutcome(output, errorCode);
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception error) {
            throw new IllegalArgumentException("tool_arguments_invalid");
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

    private String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public record AgentToolExecutionOutcome(
            String output,
            String code
    ) {
    }

    public record ToolExecutionMemo(
            String output
    ) {
    }
}
