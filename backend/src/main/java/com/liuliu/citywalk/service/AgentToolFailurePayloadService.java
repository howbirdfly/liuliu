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
                    "鐭ヨ瘑搴撶粨鏋滀笉鍙敤鏃讹紝缁х画缁撳悎鍦板浘宸ュ叿銆佺ぞ鍖哄叕寮€鍐呭鍜屽凡鏈変笂涓嬫枃缁欏嚭寤鸿锛屽苟鏄庣‘璇存槑鐭ヨ瘑搴撴湭鎴愬姛杩斿洖銆?";
            case "search_community_guides" ->
                    "绀惧尯鏀荤暐妫€绱㈠け璐ユ椂锛屼紭鍏堝洖閫€鍒板湴鍥炬悳绱㈠拰閫氱敤璺嚎寤鸿锛屼笉瑕佺紪閫犲叿浣撳笘瀛愬唴瀹广€?";
            case "search_poi", "nearby_pois" ->
                    "鍦板浘宸ュ叿澶辫触鏃讹紝鍙互鍩轰簬鐢ㄦ埛宸叉彁渚涚殑鍖哄煙銆佸巻鍙插亸濂藉拰鍏朵粬宸ュ叿缁撴灉缁欏嚭淇濆畧寤鸿锛屽苟璇存槑鍦扮偣鍑嗙‘鎬ф湁闄愩€?";
            case "get_walk_detail" ->
                    "鍗曟潯 Walk 璇︽儏鑾峰彇澶辫触鏃讹紝涓嶈鍋囪甯栧瓙缁嗚妭瀛樺湪锛岀户缁熀浜庡凡鏈夊叕寮€淇℃伅缁欏嚭姒傛嫭鎬у缓璁€?";
            default ->
                    "宸ュ叿鏈垚鍔熻繑鍥炲彲闈犵粨鏋滄椂锛屽熀浜庡凡鏈変笂涓嬫枃缁х画鍥炵瓟锛屽苟鏄庣‘鍛婄煡鐢ㄦ埛杩欎竴姝ョ己灏戝伐鍏锋敮鎾戙€?";
        };
        if ("tool_arguments_invalid".equals(errorCode)) {
            return suggestion + " 杩欐澶辫触涔熷彲鑳芥槸宸ュ叿鍙傛暟涓嶅畬鏁存垨鏍煎紡涓嶆纭€?";
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
