package com.liuliu.citywalk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.config.DeepSeekAiProperties;
import com.liuliu.citywalk.service.agent.LlmResponse;
import com.liuliu.citywalk.service.agent.SpringAiLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentStructuredIntentExtractionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extractsOnlyCurrentInstructionUsingSmallOutputBudget() {
        SpringAiLlmClient llmClient = mock(SpringAiLlmClient.class);
        DeepSeekAiProperties properties = new DeepSeekAiProperties();
        properties.setStructuredIntentExtractionMaxTokens(384);
        AgentStructuredIntentExtractionService service = new AgentStructuredIntentExtractionService(
                llmClient,
                objectMapper,
                properties
        );
        String prompt = "帮我规划上海适合拍照的路线，两小时，改成晚上从武康路开始";
        when(llmClient.createConstrainedResponse(anyString(), anyList(), eq(0.0), eq(384)))
                .thenReturn(new LlmResponse(
                        """
                                {"cities":["上海"],"areas":["武康路"],"styles":["拍照"],
                                "objectives":null,"duration":"两小时","timePreference":"晚上",
                                "mobilityPreference":null,"avoidTags":null,"useCurrentLocation":null,
                                "needsKnowledgeReference":null,"needsPoiSearch":true,
                                "needsRoutePlanning":true,"needsThemeGeneration":null,
                                "acknowledgementOnly":false,"requestLike":true}
                                """,
                        List.of(),
                        "stop"
                ));

        AgentStructuredIntentExtractionService.ExtractionResult result =
                service.extractCurrentInstruction(prompt);

        assertEquals("llm_structured_current_instruction", result.source());
        assertNull(result.errorCode());
        assertEquals(List.of("上海"), result.intent().cities());
        assertEquals(List.of("武康路"), result.intent().areas());
        assertEquals(List.of("拍照"), result.intent().styles());
        assertEquals("两小时", result.intent().duration());
        assertEquals("晚上", result.intent().timePreference());
        assertEquals(true, result.intent().needsRoutePlanning());
        verify(llmClient).createConstrainedResponse(anyString(), anyList(), eq(0.0), eq(384));
    }

    @Test
    void returnsEmptyCurrentTurnSlotsWhenModelOutputIsNotJson() {
        SpringAiLlmClient llmClient = mock(SpringAiLlmClient.class);
        DeepSeekAiProperties properties = new DeepSeekAiProperties();
        AgentStructuredIntentExtractionService service = new AgentStructuredIntentExtractionService(
                llmClient,
                objectMapper,
                properties
        );
        String prompt = "杭州，改成傍晚走两小时";
        when(llmClient.createConstrainedResponse(anyString(), anyList(), eq(0.0), eq(384)))
                .thenReturn(new LlmResponse("not-json", List.of(), "stop"));

        AgentStructuredIntentExtractionService.ExtractionResult result =
                service.extractCurrentInstruction(prompt);

        assertEquals("llm_structured_unavailable", result.source());
        assertEquals("structured_intent_extraction_failed", result.errorCode());
        assertEquals(prompt, result.intent().prompt());
        assertEquals(List.of(), result.intent().cities());
        assertEquals(List.of(), result.intent().areas());
        assertEquals("", result.intent().duration());
        assertEquals(false, result.intent().needsRoutePlanning());
    }
}
