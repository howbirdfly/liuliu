package com.liuliu.citywalk.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class AgentTodoListService {

    private static final List<String> ALLOWED_STATUSES = List.of("pending", "in_progress", "completed");

    private final ThreadLocal<TodoSession> currentSession = new ThreadLocal<>();

    public void openExecutionScope(String executionId) {
        currentSession.set(new TodoSession(normalize(executionId), new ArrayList<>(), 1));
    }

    public void closeExecutionScope() {
        currentSession.remove();
    }

    public TodoSnapshot addItem(String content, String status, String note) {
        TodoSession session = requireSession();
        String normalizedContent = normalize(content);
        if (normalizedContent.isBlank()) {
            throw new IllegalArgumentException("todolist_content_required");
        }
        TodoItem item = new TodoItem(
                session.nextId(),
                normalizedContent,
                normalizeStatus(status),
                normalize(note)
        );
        session.items().add(item);
        return snapshot(session);
    }

    public TodoSnapshot updateStatus(int id, String status, String note) {
        TodoSession session = requireSession();
        if (id <= 0) {
            throw new IllegalArgumentException("todolist_id_required");
        }
        TodoItem target = null;
        for (TodoItem item : session.items()) {
            if (item.id() == id) {
                target = item;
                break;
            }
        }
        if (target == null) {
            throw new IllegalArgumentException("todolist_item_not_found");
        }
        target.setStatus(normalizeStatus(status));
        String normalizedNote = normalize(note);
        if (!normalizedNote.isBlank()) {
            target.setNote(normalizedNote);
        }
        return snapshot(session);
    }

    public TodoSnapshot listItems() {
        return snapshot(requireSession());
    }

    private TodoSession requireSession() {
        TodoSession session = currentSession.get();
        if (session == null) {
            throw new IllegalStateException("todolist_scope_unavailable");
        }
        return session;
    }

    private TodoSnapshot snapshot(TodoSession session) {
        List<TodoItemView> items = new ArrayList<>();
        int pendingCount = 0;
        int inProgressCount = 0;
        int completedCount = 0;
        for (TodoItem item : session.items()) {
            items.add(new TodoItemView(item.id(), item.content(), item.status(), item.note()));
            switch (item.status()) {
                case "pending" -> pendingCount++;
                case "in_progress" -> inProgressCount++;
                case "completed" -> completedCount++;
                default -> {
                }
            }
        }
        return new TodoSnapshot(
                session.executionId(),
                items,
                new TodoSummary(items.size(), pendingCount, inProgressCount, completedCount)
        );
    }

    private String normalizeStatus(String status) {
        String normalized = normalize(status);
        if (normalized.isBlank()) {
            return "pending";
        }
        if (!ALLOWED_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("todolist_status_invalid");
        }
        return normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class TodoSession {
        private final String executionId;
        private final List<TodoItem> items;
        private int nextId;

        private TodoSession(String executionId, List<TodoItem> items, int nextId) {
            this.executionId = executionId;
            this.items = items;
            this.nextId = nextId;
        }

        private String executionId() {
            return executionId;
        }

        private List<TodoItem> items() {
            return items;
        }

        private int nextId() {
            return nextId++;
        }
    }

    private static final class TodoItem {
        private final int id;
        private final String content;
        private String status;
        private String note;

        private TodoItem(int id, String content, String status, String note) {
            this.id = id;
            this.content = content;
            this.status = status;
            this.note = note;
        }

        private int id() {
            return id;
        }

        private String content() {
            return content;
        }

        private String status() {
            return status;
        }

        private void setStatus(String status) {
            this.status = status;
        }

        private String note() {
            return note;
        }

        private void setNote(String note) {
            this.note = note;
        }
    }

    public record TodoSnapshot(
            String executionId,
            List<TodoItemView> items,
            TodoSummary summary
    ) {
    }

    public record TodoItemView(
            int id,
            String content,
            String status,
            String note
    ) {
    }

    public record TodoSummary(
            int total,
            int pending,
            int inProgress,
            int completed
    ) {
    }
}
