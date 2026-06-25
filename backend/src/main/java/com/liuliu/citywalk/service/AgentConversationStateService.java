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
    private final AgentConversationStateStore agentConversationStateStore;
    private final ObjectMapper objectMapper;

    public AgentConversationStateService(
            AgentIntentAnalysisService agentIntentAnalysisService,
            AgentConversationStateStore agentConversationStateStore,
            ObjectMapper objectMapper
    ) {
        this.agentIntentAnalysisService = agentIntentAnalysisService;
        this.agentConversationStateStore = agentConversationStateStore;
        this.objectMapper = objectMapper;
    }

    public ResolvedConversationState resolve(Long userId, List<LlmMessage> history, String userPrompt) {
        AgentIntentAnalysisService.AgentIntent currentIntent = agentIntentAnalysisService.analyze(userPrompt);
        AgentIntentAnalysisService.AgentIntent sessionStateIntent = toIntent(agentConversationStateStore.loadState(userId));
        AgentIntentAnalysisService.AgentIntent carryoverIntent = agentIntentAnalysisService.deriveCarryoverIntent(history);
        AgentIntentAnalysisService.AgentIntent mergedCarryoverIntent = agentIntentAnalysisService.mergeWithCarryover(
                sessionStateIntent,
                carryoverIntent
        );
        AgentIntentAnalysisService.AgentIntent effectiveIntent = agentIntentAnalysisService.mergeWithCarryover(
                currentIntent,
                mergedCarryoverIntent
        );
        return new ResolvedConversationState(
                currentIntent,
                sessionStateIntent,
                carryoverIntent,
                effectiveIntent,
                resolveLocationSlot(currentIntent, sessionStateIntent, carryoverIntent, effectiveIntent),
                resolveThemeSlot(currentIntent, sessionStateIntent, carryoverIntent, effectiveIntent),
                resolveDurationSlot(currentIntent, sessionStateIntent, carryoverIntent, effectiveIntent),
                agentIntentAnalysisService.buildCarryoverPromptContext(currentIntent, mergedCarryoverIntent, effectiveIntent)
        );
    }

    public String buildPromptContext(ResolvedConversationState state) {
        if (state == null) {
            return "";
        }

        List<String> lines = new ArrayList<>();
        lines.add("Conversation state for this turn:");
        lines.add("- Judge completeness using the effective conversation state, not only the latest user sentence.");
        lines.add("- Treat short follow-up messages as incremental updates unless the user clearly starts a new request.");
        lines.add("- If one slot is updated in the latest turn, keep the other ready slots from the effective conversation state.");
        appendSlotLine(lines, state.locationSlot());
        appendSlotLine(lines, state.themeSlot());
        appendSlotLine(lines, state.durationSlot());
        return "\n\n" + String.join("\n", lines);
    }

    public String buildStateMessage(ResolvedConversationState state) {
        if (state == null) {
            return "";
        }

        List<String> lines = new ArrayList<>();
        lines.add("Effective conversation state for this turn:");
        lines.add("- Use the merged conversation state below as the current truth for planning.");
        lines.add("- If a slot is already ready from conversation_carryover, do not ask for it again unless the user explicitly changes it.");
        lines.add("- Short messages such as location-only, duration-only, or follow-up refinement messages are valid updates, not empty requests.");
        lines.add("- Only ignore earlier context when the user explicitly starts over or asks to discard it.");
        appendSlotLine(lines, state.locationSlot());
        appendSlotLine(lines, state.themeSlot());
        appendSlotLine(lines, state.durationSlot());
        if (state.effectiveIntent() != null && !state.effectiveIntent().summary().isBlank()) {
            lines.add("- effective_intent_summary: " + state.effectiveIntent().summary());
        }
        return String.join("\n", lines);
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

    public void rememberState(Long userId, ResolvedConversationState state) {
        if (userId == null || userId <= 0 || state == null || state.effectiveIntent() == null) {
            return;
        }
        AgentIntentAnalysisService.AgentIntent effectiveIntent = state.effectiveIntent();
        if (!effectiveIntent.hasMeaningfulPlanningSignal()) {
            return;
        }
        agentConversationStateStore.saveState(userId, new AgentConversationStateStore.ConversationStateSnapshot(
                effectiveIntent.cities(),
                effectiveIntent.areas(),
                effectiveIntent.styles(),
                effectiveIntent.objectives(),
                effectiveIntent.duration(),
                effectiveIntent.timePreference(),
                effectiveIntent.mobilityPreference(),
                effectiveIntent.avoidTags(),
                effectiveIntent.useCurrentLocation(),
                System.currentTimeMillis()
        ));
    }

    public void clearState(Long userId) {
        agentConversationStateStore.clearState(userId);
    }

    private SlotResolution resolveLocationSlot(
            AgentIntentAnalysisService.AgentIntent currentIntent,
            AgentIntentAnalysisService.AgentIntent sessionStateIntent,
            AgentIntentAnalysisService.AgentIntent carryoverIntent,
            AgentIntentAnalysisService.AgentIntent effectiveIntent
    ) {
        if (!currentIntent.missingLocationContext()) {
            return new SlotResolution("location", "ready", "current_turn", describeLocation(currentIntent));
        }
        if (sessionStateIntent != null && !sessionStateIntent.missingLocationContext()) {
            return new SlotResolution("location", "ready", "session_state", describeLocation(sessionStateIntent));
        }
        if (carryoverIntent != null && !carryoverIntent.missingLocationContext()) {
            return new SlotResolution("location", "ready", "conversation_history", describeLocation(carryoverIntent));
        }
        if (!effectiveIntent.missingLocationContext()) {
            return new SlotResolution("location", "ready", "conversation_carryover", describeLocation(effectiveIntent));
        }
        return new SlotResolution("location", "missing", "none", "missing");
    }

    private SlotResolution resolveThemeSlot(
            AgentIntentAnalysisService.AgentIntent currentIntent,
            AgentIntentAnalysisService.AgentIntent sessionStateIntent,
            AgentIntentAnalysisService.AgentIntent carryoverIntent,
            AgentIntentAnalysisService.AgentIntent effectiveIntent
    ) {
        if (!currentIntent.missingThemeDirection()) {
            return new SlotResolution("theme", "ready", "current_turn", describeTheme(currentIntent));
        }
        if (sessionStateIntent != null && !sessionStateIntent.missingThemeDirection()) {
            return new SlotResolution("theme", "ready", "session_state", describeTheme(sessionStateIntent));
        }
        if (carryoverIntent != null && !carryoverIntent.missingThemeDirection()) {
            return new SlotResolution("theme", "ready", "conversation_history", describeTheme(carryoverIntent));
        }
        if (!effectiveIntent.missingThemeDirection()) {
            return new SlotResolution("theme", "ready", "conversation_carryover", describeTheme(effectiveIntent));
        }
        return new SlotResolution("theme", "missing", "none", "missing");
    }

    private SlotResolution resolveDurationSlot(
            AgentIntentAnalysisService.AgentIntent currentIntent,
            AgentIntentAnalysisService.AgentIntent sessionStateIntent,
            AgentIntentAnalysisService.AgentIntent carryoverIntent,
            AgentIntentAnalysisService.AgentIntent effectiveIntent
    ) {
        if (!currentIntent.missingDuration()) {
            return new SlotResolution("duration", "ready", "current_turn", currentIntent.duration());
        }
        if (sessionStateIntent != null && !sessionStateIntent.missingDuration()) {
            return new SlotResolution("duration", "ready", "session_state", sessionStateIntent.duration());
        }
        if (carryoverIntent != null && !carryoverIntent.missingDuration()) {
            return new SlotResolution("duration", "ready", "conversation_history", carryoverIntent.duration());
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

    private AgentIntentAnalysisService.AgentIntent toIntent(AgentConversationStateStore.ConversationStateSnapshot snapshot) {
        if (snapshot == null) {
            return agentIntentAnalysisService.analyze("");
        }
        return new AgentIntentAnalysisService.AgentIntent(
                "",
                normalizeList(snapshot.cities()),
                normalizeList(snapshot.areas()),
                normalizeList(snapshot.styles()),
                normalizeList(snapshot.objectives()),
                normalize(snapshot.duration()),
                normalize(snapshot.timePreference()),
                normalize(snapshot.mobilityPreference()),
                normalizeList(snapshot.avoidTags()),
                snapshot.useCurrentLocation(),
                false,
                false,
                false,
                false,
                false,
                false,
                List.of()
        );
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isBlank() && !result.contains(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public record ResolvedConversationState(
            AgentIntentAnalysisService.AgentIntent currentIntent,
            AgentIntentAnalysisService.AgentIntent sessionStateIntent,
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
