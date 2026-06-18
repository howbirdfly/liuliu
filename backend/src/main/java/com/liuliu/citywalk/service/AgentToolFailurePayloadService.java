package com.liuliu.citywalk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentToolFailurePayloadService {

    private final ObjectMapper objectMapper;

    public AgentToolFailurePayloadService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String build(String toolName, String errorCode, String message) {
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

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            return "{\"error\":\"json_encode_failed\"}";
        }
    }
}
