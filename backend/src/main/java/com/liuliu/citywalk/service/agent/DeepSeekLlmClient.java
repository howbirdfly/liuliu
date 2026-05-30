package com.liuliu.citywalk.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.config.DeepSeekProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DeepSeekLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekLlmClient.class);
    private static final String PROVIDER = "deepseek";

    private final DeepSeekProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DeepSeekLlmClient(DeepSeekProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public String model() {
        return properties.getModel();
    }

    @Override
    public LlmResponse createResponse(LlmRequest request) {
        if (!isConfigured()) {
            return new LlmResponse(
                    "AI 服务暂未配置完成，我先根据现有数据给出基础建议。",
                    List.of(),
                    null
            );
        }

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", properties.getModel());
            body.put("messages", buildMessages(request));
            body.put("temperature", request.temperature() == null ? 0.2 : request.temperature());
            if (request.tools() != null && !request.tools().isEmpty()) {
                body.put("tools", buildTools(request.tools()));
                body.put("tool_choice", "auto");
            }

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getBaseUrl() + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IOException("DeepSeek HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode messageNode = objectMapper.readTree(response.body())
                    .path("choices")
                    .path(0)
                    .path("message");

            String content = extractContent(messageNode.path("content"));
            List<LlmToolCall> toolCalls = extractToolCalls(messageNode.path("tool_calls"));
            return new LlmResponse(content, toolCalls, response.body());
        } catch (Exception error) {
            log.warn("DeepSeek agent call failed: {}", error.getMessage());
            return new LlmResponse(
                    "AI 规划暂时有点忙，我建议先从附近热门地点和社区攻略开始探索。",
                    List.of(),
                    null
            );
        }
    }

    private boolean isConfigured() {
        return properties.getApiKey() != null
                && !properties.getApiKey().isBlank()
                && properties.getBaseUrl() != null
                && !properties.getBaseUrl().isBlank();
    }

    private List<Map<String, Object>> buildMessages(LlmRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (request.instructions() != null && !request.instructions().isBlank()) {
            messages.add(Map.of(
                    "role", "system",
                    "content", request.instructions().trim()
            ));
        }

        if (request.messages() == null) {
            return messages;
        }

        for (LlmMessage item : request.messages()) {
            if (item == null || item.role() == null || item.role().isBlank()) {
                continue;
            }

            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", item.role());
            if (item.content() != null) {
                message.put("content", item.content());
            }
            if (item.toolCallId() != null && !item.toolCallId().isBlank()) {
                message.put("tool_call_id", item.toolCallId());
            }
            if (item.name() != null && !item.name().isBlank()) {
                message.put("name", item.name());
            }
            if (item.toolCalls() != null && !item.toolCalls().isEmpty()) {
                List<Map<String, Object>> toolCalls = new ArrayList<>();
                for (LlmToolCall toolCall : item.toolCalls()) {
                    toolCalls.add(Map.of(
                            "id", toolCall.id(),
                            "type", "function",
                            "function", Map.of(
                                    "name", toolCall.name(),
                                    "arguments", toolCall.argumentsJson()
                            )
                    ));
                }
                message.put("tool_calls", toolCalls);
            }
            messages.add(message);
        }
        return messages;
    }

    private List<Map<String, Object>> buildTools(List<LlmToolDefinition> tools) {
        List<Map<String, Object>> toolList = new ArrayList<>();
        for (LlmToolDefinition tool : tools) {
            toolList.add(Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", tool.name(),
                            "description", tool.description(),
                            "parameters", tool.parametersSchema()
                    )
            ));
        }
        return toolList;
    }

    private String extractContent(JsonNode contentNode) {
        if (contentNode == null || contentNode.isMissingNode() || contentNode.isNull()) {
            return "";
        }
        if (contentNode.isTextual()) {
            return contentNode.asText("");
        }
        if (contentNode.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode part : contentNode) {
                if (part.isTextual()) {
                    builder.append(part.asText(""));
                    continue;
                }
                if ("text".equalsIgnoreCase(part.path("type").asText())) {
                    builder.append(part.path("text").asText(""));
                }
            }
            return builder.toString().trim();
        }
        return contentNode.asText("");
    }

    private List<LlmToolCall> extractToolCalls(JsonNode toolCallsNode) {
        if (toolCallsNode == null || !toolCallsNode.isArray() || toolCallsNode.isEmpty()) {
            return List.of();
        }

        List<LlmToolCall> toolCalls = new ArrayList<>();
        for (JsonNode node : toolCallsNode) {
            JsonNode functionNode = node.path("function");
            String id = node.path("id").asText("");
            String name = functionNode.path("name").asText("");
            String argumentsJson = functionNode.path("arguments").asText("{}");
            if (name.isBlank()) {
                continue;
            }
            toolCalls.add(new LlmToolCall(id.isBlank() ? "call_" + toolCalls.size() : id, name, argumentsJson));
        }
        return toolCalls;
    }
}
