package com.liuliu.citywalk.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.model.dto.response.AgentChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Agent 批量评测:用例放在 {@code src/test/resources/agent-eval-cases.json},
 * 真实调用大模型与工具,统计工具调用正确性、格式合规率与耗时分布。
 *
 * <pre>
 * $env:AGENT_BATCH_EVAL='true'
 * $env:AGENT_BATCH_LIMIT='30'          # 可选,只跑前 N 条
 * $env:AGENT_BATCH_CATEGORY='知识库检索' # 可选,只跑某个类别
 * mvn test -Dtest=AgentBatchEvaluationTest
 * </pre>
 *
 * <p>原始结果会落到 {@code target/agent-batch-raw.json},便于离线复算指标而不用重复调用模型。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "AGENT_BATCH_EVAL", matches = "true")
@TestPropertySource(properties = {
        "liuliu.redis.agent-memory.enabled=false",
        "liuliu.redis.agent-tool-cache.enabled=${AGENT_BATCH_CACHE:false}",
        "milvus.collection=citywalk_knowledge_eval"
})
class AgentBatchEvaluationTest {

    private static final Long EVAL_USER_ID = 999_997L;
    private static final List<String> REQUIRED_SECTIONS = List.of(
            "推荐区域", "路线顺序", "依据来源", "不确定项", "实用提醒"
    );
    private static final Set<String> CACHE_CODES = Set.of(
            "tool_result_reused", "tool_result_shared_cache_hit"
    );
    /**
     * 非错误的状态码:预取结果标记、缓存复用的返回码都不代表工具失败。
     * 只有其它非空且不属于这里的 code(如 tool_arguments_invalid / tool_execution_failed)才算失败。
     */
    private static final Set<String> NON_ERROR_CODES = Set.of(
            "tool_result_reused",
            "tool_result_shared_cache_hit",
            "deterministic_prefetch"
    );

    @Autowired
    private AgentExecutionPipelineService agentExecutionPipelineService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void runBatchEvaluation() throws Exception {
        List<EvalCase> cases = loadCases();
        int passes = Math.max(1, intEnv("AGENT_BATCH_PASSES", 1));
        boolean cacheEnabled = Boolean.parseBoolean(env("AGENT_BATCH_CACHE", "false"));
        System.out.println("[agent-batch] loaded " + cases.size() + " cases, passes=" + passes
                + ", toolCache=" + cacheEnabled);

        List<List<RawCaseResult>> passResults = new ArrayList<>(passes);
        for (int pass = 1; pass <= passes; pass++) {
            List<RawCaseResult> rawResults = new ArrayList<>(cases.size());
            for (int index = 0; index < cases.size(); index++) {
                EvalCase evalCase = cases.get(index);
                RawCaseResult result = runCase(evalCase, pass);
                rawResults.add(result);
                System.out.println("[agent-batch] pass=" + pass + " " + (index + 1) + "/" + cases.size()
                        + " " + evalCase.id()
                        + " tools=" + result.toolCallCount()
                        + " executed=" + result.executedCount()
                        + " cacheHits=" + result.cacheHitCount()
                        + " latency=" + result.elapsedMillis() + "ms");
            }
            passResults.add(rawResults);
        }

        Path rawPath = Path.of("target", "agent-batch-raw.json");
        Files.createDirectories(rawPath.getParent());
        Files.writeString(rawPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(passResults));

        String report = renderReport(passResults, cacheEnabled);
        Path reportPath = Path.of("target", "agent-batch-eval-report.md");
        Files.writeString(reportPath, report);
        System.out.println("[agent-batch] report written to " + reportPath.toAbsolutePath());
        System.out.println(report);
        printSummary(passResults, cacheEnabled);
    }

    private List<EvalCase> loadCases() throws Exception {
        try (var inputStream = new ClassPathResource("agent-eval-cases.json").getInputStream()) {
            List<EvalCase> cases = objectMapper.readValue(inputStream, new TypeReference<>() {
            });
            String category = env("AGENT_BATCH_CATEGORY", "");
            if (!category.isBlank()) {
                cases = cases.stream().filter(item -> category.equals(item.category())).toList();
            }
            int limit = intEnv("AGENT_BATCH_LIMIT", cases.size());
            return cases.stream().limit(Math.max(1, limit)).toList();
        }
    }

    private RawCaseResult runCase(EvalCase evalCase, int pass) {
        List<AgentExecutionEvent> events = new ArrayList<>();
        long startNanos = System.nanoTime();
        String answer = "";
        String error = "";
        try {
            AgentChatResponse response = agentExecutionPipelineService.execute(
                    EVAL_USER_ID,
                    evalCase.prompt(),
                    null,
                    events::add
            );
            answer = response == null || response.answer() == null ? "" : response.answer();
        } catch (Exception exception) {
            error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        }
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        List<String> calledTools = new ArrayList<>();
        int toolCallCount = 0;
        int executedCount = 0;
        int cacheHitCount = 0;
        int failureCount = 0;
        List<String> failureDetails = new ArrayList<>();
        Map<String, Integer> invocationCounts = new LinkedHashMap<>();
        for (AgentExecutionEvent event : events) {
            if ("tool_call".equals(event.type())) {
                toolCallCount++;
                calledTools.add(event.name());
                String key = event.name() + "|" + (event.input() == null ? "" : event.input().trim());
                invocationCounts.merge(key, 1, Integer::sum);
                continue;
            }
            if (!"tool_result".equals(event.type())) {
                continue;
            }
            String code = event.code() == null ? "" : event.code();
            if (code.isBlank()) {
                executedCount++;
            } else if (CACHE_CODES.contains(code)) {
                cacheHitCount++;
            } else if (NON_ERROR_CODES.contains(code)) {
                executedCount++;
            } else {
                failureCount++;
                failureDetails.add(event.name() + "(" + code + ")");
            }
        }
        int redundantCount = (int) invocationCounts.values().stream().filter(count -> count > 1).count();

        boolean expectationSatisfied = evalCase.expectNoTools()
                ? toolCallCount == 0
                : calledTools.stream().anyMatch(evalCase.expectedTools()::contains);
        // 闲聊用例不要求规划格式,格式合规只对需要规划/检索的用例校验。
        boolean formatCompliant = evalCase.expectNoTools()
                || REQUIRED_SECTIONS.stream().allMatch(answer::contains);

        return new RawCaseResult(
                pass,
                evalCase.id(),
                evalCase.category(),
                evalCase.prompt(),
                List.copyOf(evalCase.expectedTools()),
                List.copyOf(calledTools),
                toolCallCount,
                executedCount,
                cacheHitCount,
                failureCount,
                List.copyOf(failureDetails),
                redundantCount,
                expectationSatisfied,
                formatCompliant,
                elapsedMillis,
                answer.length(),
                error
        );
    }

    private String renderReport(List<List<RawCaseResult>> passResults, boolean cacheEnabled) {
        List<RawCaseResult> allResults = passResults.stream().flatMap(List::stream).toList();
        int total = allResults.size();

        StringBuilder builder = new StringBuilder();
        builder.append("# Agent 批量评测\n\n");
        builder.append("- 用例数:").append(passResults.isEmpty() ? 0 : passResults.get(0).size())
                .append("(来源:src/test/resources/agent-eval-cases.json)\n");
        builder.append("- 轮次:").append(passResults.size()).append("\n");
        builder.append("- 工具共享缓存:").append(cacheEnabled ? "开启" : "关闭").append("\n");
        builder.append("- 模型:deepseek-chat;Agent 记忆:关闭\n\n");

        if (passResults.size() >= 2) {
            List<RawCaseResult> cold = passResults.get(0);
            List<RawCaseResult> warm = passResults.get(1);
            long coldExecuted = cold.stream().mapToLong(RawCaseResult::executedCount).sum();
            long warmExecuted = warm.stream().mapToLong(RawCaseResult::executedCount).sum();
            long coldCalls = cold.stream().mapToLong(RawCaseResult::toolCallCount).sum();
            long warmCalls = warm.stream().mapToLong(RawCaseResult::toolCallCount).sum();
            long warmCacheHits = warm.stream().mapToLong(RawCaseResult::cacheHitCount).sum();
            List<Long> coldLatencies = cold.stream().map(RawCaseResult::elapsedMillis).sorted().toList();
            List<Long> warmLatencies = warm.stream().map(RawCaseResult::elapsedMillis).sorted().toList();

            builder.append("## 有缓存 vs 无缓存对比(冷/热两遍)\n\n");
            builder.append("| 指标 | 第 1 遍(冷) | 第 2 遍(热) | 变化 |\n");
            builder.append("| --- | --- | --- | --- |\n");
            builder.append("| 工具调用总次数 | ").append(coldCalls).append(" | ").append(warmCalls)
                    .append(" | ").append(deltaText(coldCalls, warmCalls)).append(" |\n");
            builder.append("| 真实执行次数(打后端) | ").append(coldExecuted).append(" | ").append(warmExecuted)
                    .append(" | ").append(deltaText(coldExecuted, warmExecuted)).append(" |\n");
            builder.append("| 共享缓存命中次数 | 0 | ").append(warmCacheHits)
                    .append(" | 命中率 ").append(percent((int) warmCacheHits, (int) warmCalls)).append(" |\n");
            builder.append("| 端到端耗时 P50 | ").append(percentile(coldLatencies, 50)).append(" ms | ")
                    .append(percentile(warmLatencies, 50)).append(" ms | ")
                    .append(deltaText(percentile(coldLatencies, 50), percentile(warmLatencies, 50))).append(" |\n");
            builder.append("| 端到端耗时 P95 | ").append(percentile(coldLatencies, 95)).append(" ms | ")
                    .append(percentile(warmLatencies, 95)).append(" ms | ")
                    .append(deltaText(percentile(coldLatencies, 95), percentile(warmLatencies, 95))).append(" |\n");
            builder.append("\n> 后端调用下降率 = (第 1 遍真实执行 − 第 2 遍真实执行) / 第 1 遍真实执行。\n");
            builder.append("> 关闭缓存时,第 2 遍真实执行次数应与第 1 遍基本一致(无下降)。\n\n");
        }

        int expectationHit = (int) allResults.stream().filter(RawCaseResult::expectationSatisfied).count();
        int formatHit = (int) allResults.stream().filter(RawCaseResult::formatCompliant).count();
        int errorCases = (int) allResults.stream().filter(result -> !result.error().isBlank()).count();
        List<Long> latencies = allResults.stream().map(RawCaseResult::elapsedMillis).sorted().toList();

        builder.append("## 总体指标(全部轮次)\n\n");
        builder.append("| 指标 | 数值 |\n");
        builder.append("| --- | --- |\n");
        builder.append("| 工具期望命中率 | ").append(percent(expectationHit, total)).append(" |\n");
        builder.append("| 输出格式合规率 | ").append(percent(formatHit, total)).append(" |\n");
        builder.append("| 平均工具调用数/用例 | ")
                .append(String.format("%.2f", total == 0 ? 0D
                        : (double) allResults.stream().mapToInt(RawCaseResult::toolCallCount).sum() / total))
                .append(" |\n");
        builder.append("| 执行异常用例数 | ").append(errorCases).append(" |\n");
        builder.append("| 端到端耗时 P50 | ").append(percentile(latencies, 50)).append(" ms |\n");
        builder.append("| 端到端耗时 P95 | ").append(percentile(latencies, 95)).append(" ms |\n\n");

        builder.append("## 逐用例明细(含缓存命中)\n\n");
        builder.append("| 轮次 | ID | 类别 | 期望工具 | 实际调用 | 调用数 | 真实执行 | 缓存命中 | 失败明细 | 命中 | 格式 | 耗时 |\n");
        builder.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (RawCaseResult result : allResults) {
            builder.append("| ").append(result.pass())
                    .append(" | ").append(result.id())
                    .append(" | ").append(result.category())
                    .append(" | ").append(result.expectedTools().isEmpty()
                            ? "不应调用" : String.join(",", result.expectedTools()))
                    .append(" | ").append(result.calledTools().isEmpty() ? "-" : String.join(",", result.calledTools()))
                    .append(" | ").append(result.toolCallCount())
                    .append(" | ").append(result.executedCount())
                    .append(" | ").append(result.cacheHitCount())
                    .append(" | ").append(result.failureDetails().isEmpty()
                            ? "-" : String.join(",", result.failureDetails()))
                    .append(" | ").append(result.expectationSatisfied() ? "是" : "否")
                    .append(" | ").append(result.formatCompliant() ? "是" : "否")
                    .append(" | ").append(result.elapsedMillis()).append(" ms |\n");
        }
        return builder.toString();
    }

    private void printSummary(List<List<RawCaseResult>> passResults, boolean cacheEnabled) {
        if (passResults.size() < 2) {
            return;
        }
        List<RawCaseResult> cold = passResults.get(0);
        List<RawCaseResult> warm = passResults.get(1);
        long coldExecuted = cold.stream().mapToLong(RawCaseResult::executedCount).sum();
        long warmExecuted = warm.stream().mapToLong(RawCaseResult::executedCount).sum();
        long warmCalls = warm.stream().mapToLong(RawCaseResult::toolCallCount).sum();
        long warmHits = warm.stream().mapToLong(RawCaseResult::cacheHitCount).sum();
        long coldP50 = percentile(cold.stream().map(RawCaseResult::elapsedMillis).sorted().toList(), 50);
        long warmP50 = percentile(warm.stream().map(RawCaseResult::elapsedMillis).sorted().toList(), 50);
        long coldP95 = percentile(cold.stream().map(RawCaseResult::elapsedMillis).sorted().toList(), 95);
        long warmP95 = percentile(warm.stream().map(RawCaseResult::elapsedMillis).sorted().toList(), 95);
        System.out.println("[agent-batch-summary] cacheEnabled=" + cacheEnabled
                + " coldExecuted=" + coldExecuted
                + " warmExecuted=" + warmExecuted
                + " backendCallDrop=" + deltaText(coldExecuted, warmExecuted)
                + " warmCacheHitRate=" + percent((int) warmHits, (int) warmCalls)
                + " coldP50=" + coldP50 + "ms"
                + " warmP50=" + warmP50 + "ms"
                + " p50Drop=" + deltaText(coldP50, warmP50)
                + " coldP95=" + coldP95 + "ms"
                + " warmP95=" + warmP95 + "ms"
                + " p95Drop=" + deltaText(coldP95, warmP95));
    }

    private String deltaText(long before, long after) {
        if (before <= 0) {
            return "-";
        }
        double change = (double) (after - before) / before * 100D;
        return String.format("%+.1f%%", change);
    }

    private long percentile(List<Long> sortedValues, int percentile) {
        if (sortedValues.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(percentile / 100D * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(sortedValues.size() - 1, index)));
    }

    private String percent(int numerator, int denominator) {
        if (denominator <= 0) {
            return "0.0%";
        }
        return String.format("%.1f%%", (double) numerator / denominator * 100D);
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private int intEnv(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private record EvalCase(
            String id,
            String category,
            String prompt,
            List<String> expectedTools,
            boolean expectNoTools
    ) {
    }

    private record RawCaseResult(
            int pass,
            String id,
            String category,
            String prompt,
            List<String> expectedTools,
            List<String> calledTools,
            int toolCallCount,
            int executedCount,
            int cacheHitCount,
            int failureCount,
            List<String> failureDetails,
            int redundantInvocationCount,
            boolean expectationSatisfied,
            boolean formatCompliant,
            long elapsedMillis,
            int answerLength,
            String error
    ) {
    }
}
