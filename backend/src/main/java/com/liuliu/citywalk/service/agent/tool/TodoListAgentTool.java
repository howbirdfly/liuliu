package com.liuliu.citywalk.service.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.service.AgentTodoListService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TodoListAgentTool extends AbstractJsonAgentTool {

    private final AgentTodoListService agentTodoListService;

    public TodoListAgentTool(
            ObjectMapper objectMapper,
            AgentTodoListService agentTodoListService
    ) {
        super(objectMapper);
        this.agentTodoListService = agentTodoListService;
    }

    @Override
    public String name() {
        return "todolist";
    }

    @Override
    public String description() {
        return "Manage an execution-scoped todo list for multi-step planning. Use it to track subtasks and update progress across tool rounds.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> action = stringProperty("Operation to run: add, update_status, or list.");
        action.put("enum", List.of("add", "update_status", "list"));

        Map<String, Object> status = stringProperty("Todo status. Allowed values: pending, in_progress, completed.");
        status.put("enum", List.of("pending", "in_progress", "completed"));

        return jsonObjectSchema(
                Map.of(
                        "action", action,
                        "content", stringProperty("Todo content. Required when action=add."),
                        "id", integerProperty("Todo item id. Required when action=update_status."),
                        "status", status,
                        "note", stringProperty("Optional progress note for the todo item.")
                ),
                List.of("action")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String action = stringArg(arguments, "action");
        return switch (action) {
            case "add" -> add(arguments);
            case "update_status" -> updateStatus(arguments);
            case "list" -> list();
            default -> throw new IllegalArgumentException("todolist_action_invalid");
        };
    }

    private String add(Map<String, Object> arguments) {
        AgentTodoListService.TodoSnapshot snapshot = agentTodoListService.addItem(
                stringArg(arguments, "content"),
                stringArg(arguments, "status"),
                stringArg(arguments, "note")
        );
        return response("add", snapshot, "Todo item added.");
    }

    private String updateStatus(Map<String, Object> arguments) {
        AgentTodoListService.TodoSnapshot snapshot = agentTodoListService.updateStatus(
                intArg(arguments, "id", 0),
                stringArg(arguments, "status"),
                stringArg(arguments, "note")
        );
        return response("update_status", snapshot, "Todo item updated.");
    }

    private String list() {
        AgentTodoListService.TodoSnapshot snapshot = agentTodoListService.listItems();
        return response("list", snapshot, snapshot.items().isEmpty() ? "Todo list is empty." : "Todo list loaded.");
    }

    private String response(String action, AgentTodoListService.TodoSnapshot snapshot, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("action", action);
        payload.put("message", message);
        payload.put("executionId", snapshot.executionId());
        payload.put("items", snapshot.items());
        payload.put("summary", snapshot.summary());
        return json(payload);
    }
}
