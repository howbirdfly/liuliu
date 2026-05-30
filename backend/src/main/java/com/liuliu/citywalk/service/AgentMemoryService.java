package com.liuliu.citywalk.service;

import com.liuliu.citywalk.service.agent.LlmMessage;

import java.util.List;

public interface AgentMemoryService {

    boolean isEnabled();

    List<LlmMessage> loadConversation(Long userId);

    void appendTurn(Long userId, String userPrompt, String assistantAnswer);

    void clearConversation(Long userId);
}
