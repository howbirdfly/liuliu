package com.liuliu.citywalk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentConversationStateServiceTest {

    @Test
    void currentInstructionFieldsOverrideStateWhileUnmentionedSlotsCarryOver() {
        ObjectMapper objectMapper = new ObjectMapper();
        AgentIntentAnalysisService analyzer = new AgentIntentAnalysisService(objectMapper);
        AgentStructuredIntentExtractionService extractor = mock(AgentStructuredIntentExtractionService.class);
        AgentConversationStateStore stateStore = mock(AgentConversationStateStore.class);
        AgentConversationStateService service = new AgentConversationStateService(
                analyzer,
                extractor,
                stateStore,
                objectMapper
        );
        String prompt = "改成晚上，安静一点";
        AgentIntentAnalysisService.AgentIntent structuredCurrent = new AgentIntentAnalysisService.AgentIntent(
                prompt,
                List.of(),
                List.of(),
                List.of("安静"),
                List.of(),
                "",
                "晚上",
                "",
                List.of(),
                false,
                false,
                false,
                true,
                false,
                false,
                true,
                List.of("城市或区域", "预计时长")
        );
        when(extractor.extractCurrentInstruction(prompt))
                .thenReturn(AgentStructuredIntentExtractionService.ExtractionResult.llm(structuredCurrent));
        when(stateStore.loadState(7L)).thenReturn(new AgentConversationStateStore.ConversationStateSnapshot(
                List.of("上海"),
                List.of("武康路"),
                List.of("拍照"),
                List.of("散步"),
                "一小时",
                "下午",
                "轻松",
                List.of("人多"),
                false,
                System.currentTimeMillis()
        ));

        AgentConversationStateService.ResolvedConversationState state = service.resolve(7L, List.of(), prompt);

        assertEquals("llm_structured_current_instruction", state.currentIntentSource());
        assertEquals(List.of("上海"), state.effectiveIntent().cities());
        assertEquals(List.of("武康路"), state.effectiveIntent().areas());
        assertEquals(List.of("安静"), state.effectiveIntent().styles());
        assertEquals(List.of("散步"), state.effectiveIntent().objectives());
        assertEquals("一小时", state.effectiveIntent().duration());
        assertEquals("晚上", state.effectiveIntent().timePreference());
        assertEquals("轻松", state.effectiveIntent().mobilityPreference());
        assertEquals(List.of("人多"), state.effectiveIntent().avoidTags());
    }
}
