package com.liuliu.citywalk.service;

import com.liuliu.citywalk.service.agent.AgentExecutionCancelledException;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AgentExecutionRegistryService {

    private final ConcurrentMap<String, AgentExecutionHandle> handlesByExecutionId = new ConcurrentHashMap<>();

    public AgentExecutionHandle register(Long userId, String executionId) {
        String normalizedExecutionId = normalizeExecutionId(executionId);
        AgentExecutionHandle handle = new AgentExecutionHandle(userId, normalizedExecutionId);
        AgentExecutionHandle previous = handlesByExecutionId.put(normalizedExecutionId, handle);
        if (previous != null) {
            previous.cancel();
        }
        return handle;
    }

    public boolean cancel(Long userId, String executionId) {
        String normalizedExecutionId = normalizeExecutionId(executionId);
        AgentExecutionHandle handle = handlesByExecutionId.get(normalizedExecutionId);
        if (handle == null || !Objects.equals(handle.userId(), userId)) {
            return false;
        }
        handle.cancel();
        return true;
    }

    public void unregister(AgentExecutionHandle handle) {
        if (handle == null) {
            return;
        }
        handlesByExecutionId.remove(handle.executionId(), handle);
    }

    private String normalizeExecutionId(String executionId) {
        String normalized = executionId == null ? "" : executionId.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("execution_id_required");
        }
        return normalized;
    }

    public static final class AgentExecutionHandle {

        private final Long userId;
        private final String executionId;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<Thread> workerThread = new AtomicReference<>();

        private AgentExecutionHandle(Long userId, String executionId) {
            this.userId = userId;
            this.executionId = executionId;
        }

        public Long userId() {
            return userId;
        }

        public String executionId() {
            return executionId;
        }

        public void attachThread(Thread thread) {
            if (thread == null) {
                return;
            }
            workerThread.set(thread);
            if (cancelled.get()) {
                thread.interrupt();
            }
        }

        public void cancel() {
            cancelled.set(true);
            Thread thread = workerThread.get();
            if (thread != null) {
                thread.interrupt();
            }
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        public void checkCancelled() {
            if (!cancelled.get() && !Thread.currentThread().isInterrupted()) {
                return;
            }
            Thread.currentThread().interrupt();
            throw new AgentExecutionCancelledException("agent_execution_cancelled");
        }
    }
}
