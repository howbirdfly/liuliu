package com.liuliu.citywalk.controller;

import com.liuliu.citywalk.common.ApiResponse;
import com.liuliu.citywalk.model.dto.request.AgentChatRequest;
import com.liuliu.citywalk.model.dto.response.AgentChatResponse;
import com.liuliu.citywalk.model.dto.response.AgentStreamEventResponse;
import com.liuliu.citywalk.service.AgentOrchestratorService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private final AgentOrchestratorService agentOrchestratorService;

    public AgentController(AgentOrchestratorService agentOrchestratorService) {
        this.agentOrchestratorService = agentOrchestratorService;
    }

    @PostMapping("/chat")
    public ApiResponse<AgentChatResponse> chat(@Valid @RequestBody AgentChatRequest request) {
        return ApiResponse.success(agentOrchestratorService.chat(request.prompt()));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@RequestParam String prompt) {
        String normalizedPrompt = prompt == null ? "" : prompt.trim();
        if (normalizedPrompt.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "prompt_required");
        }

        SseEmitter emitter = new SseEmitter(5L * 60L * 1000L);
        Thread.startVirtualThread(() -> {
            try {
                agentOrchestratorService.stream(normalizedPrompt, event -> sendEvent(emitter, event));
                emitter.complete();
            } catch (Exception error) {
                emitter.completeWithError(error);
            }
        });

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    private void sendEvent(SseEmitter emitter, AgentOrchestratorService.AgentExecutionEvent event) {
        try {
            AgentStreamEventResponse payload = new AgentStreamEventResponse(
                    event.type(),
                    event.name(),
                    event.input(),
                    event.output(),
                    event.iteration(),
                    null,
                    null
            );
            emitter.send(
                    SseEmitter.event()
                            .name(event.type())
                            .data(payload, MediaType.APPLICATION_JSON)
            );
        } catch (Exception error) {
            throw new IllegalStateException("agent_stream_send_failed", error);
        }
    }
}
