package com.liuliu.citywalk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.service.agent.LlmMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class AgentConversationStateService {

    private final AgentIntentAnalysisService agentIntentAnalysisService;
    private final ObjectMapper objectMapper;

    public AgentConversationStateService(
            AgentIntentAnalysisService agentIntentAnalysisService,
            ObjectMapper objectMapper
    ) {
        this.agentIntentAnalysisService = agentIntentAnalysisService;
        this.objectMapper = objectMapper;
    }

    public ResolvedConversationState resolve(List<LlmMessage> history, String userPrompt) {
        AgentIntentAnalysisService.AgentIntent currentIntent = agentIntentAnalysisService.analyze(userPrompt);
        AgentIntentAnalysisService.AgentIntent carryoverIntent = agentIntentAnalysisService.deriveCarryoverIntent(history);
        AgentIntentAnalysisService.AgentIntent effectiveIntent = agentIntentAnalysisService.mergeWithCarryover(
                currentIntent,
                carryoverIntent
        );
        return new ResolvedConversationState(
                currentIntent,
                carryoverIntent,
                effectiveIntent,
                resolveLocationSlot(currentIntent, effectiveIntent),
                resolveThemeSlot(currentIntent, effectiveIntent),
                resolveDurationSlot(currentIntent, effectiveIntent),
                agentIntentAnalysisService.buildCarryoverPromptContext(currentIntent, carryoverIntent, effectiveIntent)
        );
    }

    public String buildPromptContext(ResolvedConversationState state) {
        if (state == null) {
            return "";
        }

        List<String> lines = new ArrayList<>();
        lines.add("Conversation state for this turn:");
        lines.add("- Judge completeness using the effective conversation state, not only the latest user sentence.");
        appendSlotLine(lines, state.locationSlot());
        appendSlotLine(lines, state.themeSlot());
        appendSlotLine(lines, state.durationSlot());
        return "\n\n" + String.join("\n", lines);
    }

    public String toStepOutput(ResolvedConversationState state) {
        if (state == null) {
            return "{}";
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state);
        } catch (JsonProcessingException ignored) {
            return "{effective_intent_summary=" + state.effectiveIntent().summary() + "}";
        }
    }

    private SlotResolution resolveLocationSlot(
            AgentIntentAnalysisService.AgentIntent currentIntent,
            AgentIntentAnalysisService.AgentIntent effectiveIntent
    ) {
        if (!currentIntent.missingLocationContext()) {
            return new SlotResolution("location", "ready", "current_turn", describeLocation(currentIntent));
        }
        if (!effectiveIntent.missingLocationContext()) {
            return new SlotResolution("location", "ready", "conversation_carryover", describeLocation(effectiveIntent));
        }
        return new SlotResolution("location", "missing", "none", "missing");
    }

    private SlotResolution resolveThemeSlot(
            AgentIntentAnalysisService.AgentIntent currentIntent,
            AgentIntentAnalysisService.AgentIntent effectiveIntent
    ) {
        if (!currentIntent.missingThemeDirection()) {
            return new SlotResolution("theme", "ready", "current_turn", describeTheme(currentIntent));
        }
        if (!effectiveIntent.missingThemeDirection()) {
            return new SlotResolution("theme", "ready", "conversation_carryover", describeTheme(effectiveIntent));
        }
        return new SlotResolution("theme", "missing", "none", "missing");
    }

    private SlotResolution resolveDurationSlot(
            AgentIntentAnalysisService.AgentIntent currentIntent,
            AgentIntentAnalysisService.AgentIntent effectiveIntent
    ) {
        if (!currentIntent.missingDuration()) {
            return new SlotResolution("duration", "ready", "current_turn", currentIntent.duration());
        }
        if (!effectiveIntent.missingDuration()) {
            return new SlotResolution("duration", "ready", "conversation_carryover", effectiveIntent.duration());
        }
        return new SlotResolution("duration", "missing", "none", "missing");
    }

    private void appendSlotLine(List<String> lines, SlotResolution slot) {
        if (slot == null) {
            return;
        }
        lines.add("- " + slot.slot() + " slot: " + slot.status() + ", source=" + slot.source() + ", value=" + slot.value());
    }

    private String describeLocation(AgentIntentAnalysisService.AgentIntent intent) {
        List<String> parts = new ArrayList<>();
        if (!intent.cities().isEmpty()) {
            parts.add("city=" + String.join("/", intent.cities()));
        }
        if (!intent.areas().isEmpty()) {
            parts.add("area=" + String.join("/", intent.areas()));
        }
        if (intent.useCurrentLocation()) {
            parts.add("current_location=true");
        }
        return parts.isEmpty() ? "missing" : String.join(", ", parts);
    }

    private String describeTheme(AgentIntentAnalysisService.AgentIntent intent) {
        List<String> parts = new ArrayList<>();
        if (!intent.styles().isEmpty()) {
            parts.add("style=" + String.join("/", intent.styles()));
        }
        if (!intent.objectives().isEmpty()) {
            parts.add("goal=" + String.join("/", intent.objectives()));
        }
        return parts.isEmpty() ? "missing" : String.join(", ", parts);
    }

    public record ResolvedConversationState(
            AgentIntentAnalysisService.AgentIntent currentIntent,
            AgentIntentAnalysisService.AgentIntent carryoverIntent,
            AgentIntentAnalysisService.AgentIntent effectiveIntent,
            SlotResolution locationSlot,
            SlotResolution themeSlot,
            SlotResolution durationSlot,
            String carryoverPromptContext
    ) {
    }

    public record SlotResolution(
            String slot,
            String status,
            String source,
            String value
    ) {
    }
}
