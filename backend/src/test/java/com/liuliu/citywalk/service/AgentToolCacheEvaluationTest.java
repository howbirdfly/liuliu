package com.liuliu.citywalk.service;

import com.liuliu.citywalk.model.dto.response.AgentChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具结果缓存评测:量化 Redis 共享缓存对「重复工具调用」的节省。
 *
 * <p>Part A(工具级,可复现):用同一组参数连续调用同一个工具两次(每次都用新的执行内 memo,确保第二次必须走 Redis),
 * 对比第一次(真实执行,通常要打 Milvus / 高德)与第二次(命中共享缓存)的耗时与返回码。
 *
 * <p>Part B(Agent 级):同一个用户请求跑两遍,统计工具真实执行次数、共享缓存命中次数与端到端耗时。
 *
 * <pre>
 * $env:AGENT_CACHE_EVAL='true'
 * mvn test -Dtest=AgentToolCacheEvaluationTest
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "AGENT_CACHE_EVAL", matches = "true")
@TestPropertySource(properties = {
        "liuliu.redis.agent-tool-cache.enabled=true",
        "liuliu.redis.agent-memory.enabled=false",
        "milvus.collection=citywalk_knowledge_eval"
})
class AgentToolCacheEvaluationTest {

    private static final Long EVAL_USER_ID = 999_998L;

    @Autowired
    private AgentToolExecutionService agentToolExecutionService;

    @Autowired
    private AgentExecutionPipelineService agentExecutionPipelineService;

    @Test
    void measureSharedToolCacheEffect() throws Exception {
        String runToken = Long.toString(System.currentTimeMillis());
        List<ToolProbe> probes = List.of(
                new ToolProbe("search_knowledge_base",
                        "{\"query\":\"武康路 拍照 咖啡 " + runToken + "\",\"topK\":5,\"sourceType\":\"community_walk\"}"),
                new ToolProbe("search_knowledge_base",
                        "{\"query\":\"永庆坊 骑楼 老街 " + runToken + "\",\"topK\":5,\"sourceType\":\"community_walk\"}"),
                new ToolProbe("search_poi", "{\"query\":\"外滩 夜景 " + runToken + "\"}")
        );

        List<ProbeResult> probeResults = new ArrayList<>();
        for (ToolProbe probe : probes) {
            CallOutcome cold = callOnce(probe);
            CallOutcome warm = callOnce(probe);
            probeResults.add(new ProbeResult(probe, cold, warm));
        }

        String prompt = "帮我规划一条上海武康路的半日 citywalk 路线,喜欢拍照和咖啡";
        RunSummary firstRun = runAgent(prompt);
        RunSummary secondRun = runAgent(prompt);

        String report = renderReport(probeResults, firstRun, secondRun);
        Path reportPath = Path.of("target", "agent-tool-cache-eval-report.md");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report);
        System.out.println("[agent-cache-eval] report written to " + reportPath.toAbsolutePath());
        System.out.println(report);
    }

    private CallOutcome callOnce(ToolProbe probe) {
        Map<String, AgentToolExecutionService.ToolExecutionMemo> memo = new ConcurrentHashMap<>();
        long startNanos = System.nanoTime();
        AgentToolExecutionService.AgentToolExecutionOutcome outcome = agentToolExecutionService.executePrefetched(
                probe.toolName(),
                probe.argumentsJson(),
                () -> {
                },
                memo
        );
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        return new CallOutcome(outcome.code(), elapsedMillis, outputLength(outcome.output()));
    }

    private RunSummary runAgent(String prompt) {
        List<AgentExecutionEvent> events = new ArrayList<>();
        long startNanos = System.nanoTime();
        AgentChatResponse response = agentExecutionPipelineService.execute(
                EVAL_USER_ID,
                prompt,
                null,
                events::add
        );
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        int toolCallCount = 0;
        int executedCount = 0;
        int sharedCacheHitCount = 0;
        int memoReuseCount = 0;
        for (AgentExecutionEvent event : events) {
            if (!"tool_result".equals(event.type())) {
                continue;
            }
            toolCallCount++;
            String code = event.code() == null ? "" : event.code();
            switch (code) {
                case "tool_result_shared_cache_hit" -> sharedCacheHitCount++;
                case "tool_result_reused" -> memoReuseCount++;
                default -> executedCount++;
            }
        }
        return new RunSummary(
                elapsedMillis,
                toolCallCount,
                executedCount,
                sharedCacheHitCount,
                memoReuseCount,
                response == null ? 0 : response.answer() == null ? 0 : response.answer().length()
        );
    }

    private String renderReport(List<ProbeResult> probeResults, RunSummary firstRun, RunSummary secondRun) {
        StringBuilder builder = new StringBuilder();
        builder.append("# 工具结果共享缓存评测\n\n");
        builder.append("- 缓存实现:Redis(AgentToolResultCacheService),TTL 300s,key = SHA-256(tool + 规范化参数)\n");
        builder.append("- 仅对声明幂等可重放的工具启用(检索类 5 个工具),todo 等有状态工具不缓存\n\n");

        builder.append("## Part A:同一工具调用连续两次(第二次强制走共享缓存)\n\n");
        builder.append("| 工具 | 参数 | 第一次返回码 | 第一次耗时 | 第二次返回码 | 第二次耗时 | 节省 |\n");
        builder.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (ProbeResult result : probeResults) {
            builder.append("| ").append(result.probe().toolName())
                    .append(" | ").append(escape(result.probe().argumentsJson()))
                    .append(" | ").append(codeText(result.cold().code()))
                    .append(" | ").append(result.cold().elapsedMillis()).append(" ms")
                    .append(" | ").append(codeText(result.warm().code()))
                    .append(" | ").append(result.warm().elapsedMillis()).append(" ms")
                    .append(" | ").append(savingText(result.cold().elapsedMillis(), result.warm().elapsedMillis()))
                    .append(" |\n");
        }

        long coldTotal = probeResults.stream().mapToLong(result -> result.cold().elapsedMillis()).sum();
        long warmTotal = probeResults.stream().mapToLong(result -> result.warm().elapsedMillis()).sum();
        long cacheHits = probeResults.stream().filter(result -> "tool_result_shared_cache_hit".equals(result.warm().code())).count();
        builder.append("\n- 二次调用共享缓存命中率:").append(percent((int) cacheHits, probeResults.size())).append("\n");
        builder.append("- 三次真实执行总耗时:").append(coldTotal).append(" ms\n");
        builder.append("- 三次缓存命中总耗时:").append(warmTotal).append(" ms\n");
        builder.append("- 重复调用耗时下降:").append(savingText(coldTotal, warmTotal)).append("\n\n");

        builder.append("## Part B:同一个 Agent 请求跑两遍\n\n");
        builder.append("| 轮次 | 端到端耗时 | 工具调用次数 | 真实执行 | 共享缓存命中 | 执行内复用 |\n");
        builder.append("| --- | --- | --- | --- | --- | --- |\n");
        builder.append("| 第 1 遍(冷) | ").append(firstRun.elapsedMillis()).append(" ms | ")
                .append(firstRun.toolCallCount()).append(" | ")
                .append(firstRun.executedCount()).append(" | ")
                .append(firstRun.sharedCacheHitCount()).append(" | ")
                .append(firstRun.memoReuseCount()).append(" |\n");
        builder.append("| 第 2 遍(热) | ").append(secondRun.elapsedMillis()).append(" ms | ")
                .append(secondRun.toolCallCount()).append(" | ")
                .append(secondRun.executedCount()).append(" | ")
                .append(secondRun.sharedCacheHitCount()).append(" | ")
                .append(secondRun.memoReuseCount()).append(" |\n");

        int totalSecondCalls = secondRun.toolCallCount();
        builder.append("\n- 第 2 遍工具执行减少:")
                .append(firstRun.executedCount()).append(" → ").append(secondRun.executedCount())
                .append(" 次\n");
        if (totalSecondCalls > 0) {
            builder.append("- 第 2 遍共享缓存命中率:")
                    .append(percent(secondRun.sharedCacheHitCount(), totalSecondCalls)).append("\n");
        }
        builder.append("\n> 说明:大模型每次生成的工具参数可能不同(即使 prompt 相同),参数不一致时不会命中缓存,\n");
        builder.append("> 因此 Part B 的命中率天然低于 Part A;工具缓存主要消除「相同参数」的重复外部调用。\n");
        return builder.toString();
    }

    private String codeText(String code) {
        if (code == null || code.isBlank()) {
            return "真实执行";
        }
        return switch (code) {
            case "tool_result_shared_cache_hit" -> "共享缓存命中";
            case "tool_result_reused" -> "执行内复用";
            default -> code;
        };
    }

    private String savingText(long before, long after) {
        if (before <= 0) {
            return "-";
        }
        double savedPercent = (double) (before - after) / before * 100D;
        return String.format("%.1f%%", savedPercent);
    }

    private String percent(int numerator, int denominator) {
        if (denominator <= 0) {
            return "0.0%";
        }
        return String.format("%.1f%%", (double) numerator / denominator * 100D);
    }

    private int outputLength(String output) {
        return output == null ? 0 : output.length();
    }

    private String escape(String value) {
        String normalized = value == null ? "" : value;
        return normalized.replace("|", "\\|").replace("\n", " ");
    }

    private record ToolProbe(String toolName, String argumentsJson) {
    }

    private record CallOutcome(String code, long elapsedMillis, int outputLength) {
    }

    private record ProbeResult(ToolProbe probe, CallOutcome cold, CallOutcome warm) {
    }

    private record RunSummary(
            long elapsedMillis,
            int toolCallCount,
            int executedCount,
            int sharedCacheHitCount,
            int memoReuseCount,
            int answerLength
    ) {
    }
}
