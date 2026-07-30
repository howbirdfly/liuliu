package com.liuliu.citywalk.service.agent.hook;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentExecutionHookRegistryService {

    private final Map<AgentExecutionHookPoint, List<AgentExecutionHook>> hooksByPoint;

    public AgentExecutionHookRegistryService(List<AgentExecutionHook> hooks) {
        EnumMap<AgentExecutionHookPoint, List<AgentExecutionHook>> registry =
                new EnumMap<>(AgentExecutionHookPoint.class);
        for (AgentExecutionHookPoint point : AgentExecutionHookPoint.values()) {
            registry.put(point, new ArrayList<>());
        }
        List<AgentExecutionHook> sortedHooks = hooks == null ? List.of() : new ArrayList<>(hooks);
        sortedHooks.sort(Comparator.comparingInt(AgentExecutionHook::order));
        for (AgentExecutionHook hook : sortedHooks) {
            if (hook == null || hook.hookPoints() == null || hook.hookPoints().isEmpty()) {
                continue;
            }
            for (AgentExecutionHookPoint point : hook.hookPoints()) {
                if (point != null) {
                    registry.get(point).add(hook);
                }
            }
        }
        this.hooksByPoint = registry;
    }

    public void trigger(AgentExecutionHookPoint point, AgentExecutionHookContext context) {
        if (point == null || context == null) {
            return;
        }
        List<AgentExecutionHook> hooks = hooksByPoint.get(point);
        if (hooks == null || hooks.isEmpty()) {
            return;
        }
        for (AgentExecutionHook hook : hooks) {
            hook.handle(context);
        }
    }
}
