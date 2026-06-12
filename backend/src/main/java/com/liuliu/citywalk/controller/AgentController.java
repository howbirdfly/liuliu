package com.liuliu.citywalk.controller;

import com.liuliu.citywalk.common.ApiResponse;
import com.liuliu.citywalk.context.BaseContext;
import com.liuliu.citywalk.model.dto.request.AgentCancelRequest;
import com.liuliu.citywalk.model.dto.request.AgentChatRequest;
import com.liuliu.citywalk.model.dto.request.AgentStreamInitRequest;
import com.liuliu.citywalk.model.dto.response.AgentChatResponse;
import com.liuliu.citywalk.model.dto.response.AgentStreamEventResponse;
import com.liuliu.citywalk.model.dto.response.AgentStreamInitResponse;
import com.liuliu.citywalk.model.dto.response.OperationResultResponse;
import com.liuliu.citywalk.service.AgentExecutionRegistryService;
import com.liuliu.citywalk.service.AgentOrchestratorService;
import com.liuliu.citywalk.service.AgentStreamAccessService;
import com.liuliu.citywalk.service.UserSessionService;
import com.liuliu.citywalk.service.agent.AgentExecutionCancelledException;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private final AgentOrchestratorService agentOrchestratorService;
    private final AgentExecutionRegistryService agentExecutionRegistryService;
    private final AgentStreamAccessService agentStreamAccessService;
    private final UserSessionService userSessionService;

    public AgentController(
            AgentOrchestratorService agentOrchestratorService,
            AgentExecutionRegistryService agentExecutionRegistryService,
            AgentStreamAccessService agentStreamAccessService,
            UserSessionService userSessionService
    ) {
        this.agentOrchestratorService = agentOrchestratorService;
        this.agentExecutionRegistryService = agentExecutionRegistryService;
        this.agentStreamAccessService = agentStreamAccessService;
        this.userSessionService = userSessionService;
    }

    @PostMapping("/chat")
    public ApiResponse<AgentChatResponse> chat(@Valid @RequestBody AgentChatRequest request) {
        return ApiResponse.success(agentOrchestratorService.chat(BaseContext.requireCurrentUserId(), request.prompt()));
    }

    @PostMapping("/memory/clear")
    public ApiResponse<OperationResultResponse> clearMemory() {
        agentOrchestratorService.clearConversation(BaseContext.requireCurrentUserId());
        return ApiResponse.success(new OperationResultResponse(true));
    }

    @PostMapping("/cancel")
    public ApiResponse<OperationResultResponse> cancel(@Valid @RequestBody AgentCancelRequest request) {
        boolean cancelled = agentExecutionRegistryService.cancel(BaseContext.requireCurrentUserId(), request.executionId());
        return ApiResponse.success(new OperationResultResponse(cancelled));
    }

    @PostMapping("/stream/init")
    public ApiResponse<AgentStreamInitResponse> initStream(@Valid @RequestBody AgentStreamInitRequest request) {
        String normalizedExecutionId = request.executionId().trim();
        AgentStreamAccessService.StreamTokenGrant grant =
                agentStreamAccessService.issue(BaseContext.requireCurrentUserId(), normalizedExecutionId);
        return ApiResponse.success(new AgentStreamInitResponse(
                normalizedExecutionId,
                grant.token(),
                grant.expiresInSeconds()
        ));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(
            @RequestParam String prompt,
            @RequestParam String executionId,
            @RequestParam String streamToken
    ) {
        String normalizedPrompt = prompt == null ? "" : prompt.trim();
        if (normalizedPrompt.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "prompt_required");
        }
        String normalizedExecutionId = executionId == null ? "" : executionId.trim();
        if (normalizedExecutionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "execution_id_required");
        }

        UserSessionService.StoredUser currentUser = resolveStreamUser(streamToken, normalizedExecutionId);
        if (currentUser == null || currentUser.isGuest()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "login_required");
        }

        SseEmitter emitter = new SseEmitter(5L * 60L * 1000L);
        AgentExecutionRegistryService.AgentExecutionHandle executionHandle =
                agentExecutionRegistryService.register(currentUser.id(), normalizedExecutionId);

        emitter.onCompletion(executionHandle::cancel);
        emitter.onTimeout(executionHandle::cancel);
        emitter.onError(error -> executionHandle.cancel());

        Thread workerThread = Thread.startVirtualThread(() -> {
            try {
                agentOrchestratorService.stream(
                        currentUser.id(),
                        normalizedPrompt,
                        executionHandle,
                        event -> sendEvent(emitter, event)
                );
                emitter.complete();
            } catch (AgentExecutionCancelledException ignored) {
                emitter.complete();
            } catch (Exception error) {
                if (executionHandle.isCancelled()) {
                    emitter.complete();
                } else {
                    emitErrorAndComplete(emitter, error);
                }
            } finally {
                agentExecutionRegistryService.unregister(executionHandle);
            }
        });
        executionHandle.attachThread(workerThread);

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    private UserSessionService.StoredUser resolveStreamUser(String streamToken, String executionId) {
        Long userId = agentStreamAccessService.consumeUserId(streamToken, executionId);
        if (userId == null || userId <= 0) {
            return null;
        }
        return userSessionService.loadUserById(userId);
    }

    private void sendEvent(SseEmitter emitter, AgentOrchestratorService.AgentExecutionEvent event) {
        try {
            AgentStreamEventResponse payload = new AgentStreamEventResponse(
                    event.type(),
                    event.name(),
                    event.input(),
                    event.output(),
                    event.iteration(),
                    event.provider(),
                    event.model(),
                    event.code()
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

    private void emitErrorAndComplete(SseEmitter emitter, Exception error) {
        String message = extractErrorMessage(error);
        try {
            sendEvent(emitter, new AgentOrchestratorService.AgentExecutionEvent(
                    "agent_error",
                    "agent",
                    null,
                    message,
                    0,
                    null,
                    null,
                    "agent_stream_failed"
            ));
            emitter.complete();
        } catch (Exception sendError) {
            emitter.completeWithError(sendError);
        }
    }

    private String extractErrorMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getMessage();
        }
        return message == null || message.isBlank() ? "agent_stream_failed" : message;
    }
}
