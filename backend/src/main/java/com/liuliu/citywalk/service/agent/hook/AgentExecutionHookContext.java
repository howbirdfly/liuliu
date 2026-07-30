package com.liuliu.citywalk.service.agent.hook;

import com.liuliu.citywalk.service.AgentExecutionEvent;
import com.liuliu.citywalk.service.AgentExecutionListener;
import com.liuliu.citywalk.model.dto.response.AgentStepResponse;
import com.liuliu.citywalk.service.AgentConversationStateService;
import com.liuliu.citywalk.service.AgentIntentAnalysisService;
import com.liuliu.citywalk.service.AgentRoundService;
import com.liuliu.citywalk.service.AgentToolExecutionService;
import com.liuliu.citywalk.service.agent.LlmMessage;
import com.liuliu.citywalk.service.agent.LlmResponse;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AgentExecutionHookContext {

    private final AgentExecutionHookPoint point;
    private final Long userId;
    private final String executionId;
    private final String normalizedPrompt;
    private final AgentIntentAnalysisService.AgentIntent intent;
    private final AgentConversationStateService.ResolvedConversationState conversationState;
    private final String instructions;
    private final List<LlmMessage> messages;
    private final List<AgentStepResponse> steps;
    private final Map<String, AgentToolExecutionService.ToolExecutionMemo> toolExecutionMemoByKey;
    private AgentExecutionListener executionListener;
    private String provider;
    private String model;
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private int round;
    private String knowledgeQuery;
    private Runnable cancellationCheck;
    private AssistantMessage.ToolCall toolCall;
    private AgentToolExecutionService.AgentToolExecutionOutcome toolOutcome;
    private LlmResponse llmResponse;
    private AgentRoundService.AgentRoundOutcome roundOutcome;
    private String finalAnswer;

    public AgentExecutionHookContext(
            AgentExecutionHookPoint point,
            Long userId,
            String executionId,
            String normalizedPrompt,
            AgentIntentAnalysisService.AgentIntent intent,
            AgentConversationStateService.ResolvedConversationState conversationState,
            String instructions,
            List<LlmMessage> messages,
            List<AgentStepResponse> steps,
            Map<String, AgentToolExecutionService.ToolExecutionMemo> toolExecutionMemoByKey
    ) {
        this.point = point;
        this.userId = userId;
        this.executionId = executionId;
        this.normalizedPrompt = normalizedPrompt;
        this.intent = intent;
        this.conversationState = conversationState;
        this.instructions = instructions;
        this.messages = messages;
        this.steps = steps;
        this.toolExecutionMemoByKey = toolExecutionMemoByKey;
    }

    public AgentExecutionHookPoint point() {
        return point;
    }

    public Long userId() {
        return userId;
    }

    public String executionId() {
        return executionId;
    }

    public String normalizedPrompt() {
        return normalizedPrompt;
    }

    public AgentIntentAnalysisService.AgentIntent intent() {
        return intent;
    }

    public AgentConversationStateService.ResolvedConversationState conversationState() {
        return conversationState;
    }

    public String instructions() {
        return instructions;
    }

    public List<LlmMessage> messages() {
        return messages;
    }

    public List<AgentStepResponse> steps() {
        return steps;
    }

    public Map<String, AgentToolExecutionService.ToolExecutionMemo> toolExecutionMemoByKey() {
        return toolExecutionMemoByKey;
    }

    public AgentExecutionListener executionListener() {
        return executionListener;
    }

    public AgentExecutionHookContext withExecutionListener(AgentExecutionListener executionListener) {
        this.executionListener = executionListener;
        return this;
    }

    public String provider() {
        return provider;
    }

    public AgentExecutionHookContext withProvider(String provider) {
        this.provider = provider;
        return this;
    }

    public String model() {
        return model;
    }

    public AgentExecutionHookContext withModel(String model) {
        this.model = model;
        return this;
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    public AgentExecutionHookContext withAttribute(String key, Object value) {
        if (key != null && !key.isBlank()) {
            attributes.put(key, value);
        }
        return this;
    }

    public int round() {
        return round;
    }

    public AgentExecutionHookContext withRound(int round) {
        this.round = round;
        return this;
    }

    public String knowledgeQuery() {
        return knowledgeQuery;
    }

    public AgentExecutionHookContext withKnowledgeQuery(String knowledgeQuery) {
        this.knowledgeQuery = knowledgeQuery;
        return this;
    }

    public Runnable cancellationCheck() {
        return cancellationCheck;
    }

    public AgentExecutionHookContext withCancellationCheck(Runnable cancellationCheck) {
        this.cancellationCheck = cancellationCheck;
        return this;
    }

    public AssistantMessage.ToolCall toolCall() {
        return toolCall;
    }

    public AgentExecutionHookContext withToolCall(AssistantMessage.ToolCall toolCall) {
        this.toolCall = toolCall;
        return this;
    }

    public AgentToolExecutionService.AgentToolExecutionOutcome toolOutcome() {
        return toolOutcome;
    }

    public AgentExecutionHookContext withToolOutcome(AgentToolExecutionService.AgentToolExecutionOutcome toolOutcome) {
        this.toolOutcome = toolOutcome;
        return this;
    }

    public LlmResponse llmResponse() {
        return llmResponse;
    }

    public AgentExecutionHookContext withLlmResponse(LlmResponse llmResponse) {
        this.llmResponse = llmResponse;
        return this;
    }

    public AgentRoundService.AgentRoundOutcome roundOutcome() {
        return roundOutcome;
    }

    public AgentExecutionHookContext withRoundOutcome(AgentRoundService.AgentRoundOutcome roundOutcome) {
        this.roundOutcome = roundOutcome;
        return this;
    }

    public String finalAnswer() {
        return finalAnswer;
    }

    public AgentExecutionHookContext withFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer;
        return this;
    }

    public void emit(AgentExecutionEvent event) {
        if (executionListener != null && event != null) {
            executionListener.onEvent(event);
        }
    }
}
