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
        "liuliu.redis.agent-tool-cache.enabled=false",
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

    @Autowired
    private AgentExecutionPipelineService agentExecutionPipelineService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void runBatchEvaluation() throws Exception {
        List<EvalCase> cases = loadCases();
        System.out.println("[agent-batch] loaded " + cases.size() + " cases");

        List<RawCaseResult> rawResults = new ArrayList<>(cases.size());
        for (int index = 0; index < cases.size(); index++) {
            EvalCase evalCase = cases.get(index);
            RawCaseResult result = runCase(evalCase);
            rawResults.add(result);
            System.out.println("[agent-batch] " + (index + 1) + "/" + cases.size()
                    + " " + evalCase.id()
                    + " tools=" + result.calledTools()
                    + " latency=" + result.elapsedMillis() + "ms");
        }

        Path rawPath = Path.of("target", "agent-batch-raw.json");
        Files.createDirectories(rawPath.getParent());
        Files.writeString(rawPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(rawResults));

        String report = renderReport(rawResults);
        Path reportPath = Path.of("target", "agent-batch-eval-report.md");
        Files.writeString(reportPath, report);
        System.out.println("[agent-batch] report written to " + reportPath.toAbsolutePath());
        System.out.println(report);
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

    private RawCaseResult runCase(EvalCase evalCase) {
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
            } else {
                failureCount++;
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
                evalCase.id(),
                evalCase.category(),
                evalCase.prompt(),
                List.copyOf(calledTools),
                toolCallCount,
                executedCount,
                cacheHitCount,
                failureCount,
                redundancyCount(),
                redundantCount,
                expectationSatisfied,
                formatCompliant,
                elapsedMillis,
                answer.length(),
                error
        );
    }

    private int redundancyCount() {
        return 0;
    }

    private String renderReport(List<RawCaseResult> results) {
        int total = results.size();
        int expectationHit = (int) results.stream().filter(RawCaseResult::expectationSatisfied).count();
        int formatHit = (int) results.stream().filter(RawCaseResult::formatCompliant).count();
        int totalToolCalls = results.stream().mapToInt(RawCaseResult::toolCallCount).sum();
        int totalExecuted = results.stream().mapToInt(RawCaseResult::executedCount).sum();
        int totalFailures = results.stream().mapToInt(RawCaseResult::failureCount).sum();
        int totalRedundant = results.stream().mapToInt(RawCaseResult::redundantInvocationCount).sum();
        int errorCases = (int) results.stream().filter(result -> !result.error().isBlank()).count();
        List<Long> latencies = results.stream().map(RawCaseResult::elapsedMillis).sorted().toList();

        StringBuilder builder = new StringBuilder();
        builder.append("# Agent 批量评测\n\n");
        builder.append("- 用例数:").append(total).append("(来源:src/test/resources/agent-eval-cases.json)\n");
        builder.append("- 模型:deepseek-chat;工具:知识库检索 / POI / 社区攻略 / 路线详情\n");
        builder.append("- 配置:Agent 记忆关闭;工具共享缓存关闭(缓存效果由单独的缓存评测衡量)\n\n");

        builder.append("## 总体指标\n\n");
        builder.append("| 指标 | 数值 |\n");
        builder.append("| --- | --- |\n");
        builder.append("| 工具期望命中率 | ").append(percent(expectationHit, total)).append(" |\n");
        builder.append("| 输出格式合规率 | ").append(percent(formatHit, total)).append(" |\n");
        builder.append("| 工具调用总数 | ").append(totalToolCalls).append(" |\n");
        builder.append("| 平均工具调用数/用例 | ")
                .append(String.format("%.2f", total == 0 ? 0D : (double) totalToolCalls / total)).append(" |\n");
        builder.append("| 真实工具执行次数 | ").append(totalExecuted).append(" |\n");
        builder.append("| 工具失败次数 | ").append(totalFailures).append(" |\n");
        builder.append("| 工具失败率 | ").append(percent(totalFailures, totalToolCalls)).append(" |\n");
        builder.append("| 冗余调用(同参重复) | ").append(totalRedundant).append(" |\n");
        builder.append("| 执行异常用例数 | ").append(errorCases).append(" |\n");
        builder.append("| 端到端耗时 P50 | ").append(percentile(latencies, 50)).append(" ms |\n");
        builder.append("| 端到端耗时 P95 | ").append(percentile(latencies, 95)).append(" ms |\n");

        builder.append("\n## 分类指标\n\n");
        builder.append("| 类别 | 用例数 | 命中率 | 格式合规率 | 平均调用数 | 失败次数 |\n");
        builder.append("| --- | --- | --- | --- | --- | --- |\n");
        Map<String, List<RawCaseResult>> byCategory = new TreeMap<>();
        results.forEach(result -> byCategory.computeIfAbsent(result.category(), key -> new ArrayList<>()).add(result));
        for (Map.Entry<String, List<RawCaseResult>> entry : byCategory.entrySet()) {
            List<RawCaseResult> group = entry.getValue();
            int groupHit = (int) group.stream().filter(RawCaseResult::expectationSatisfied).count();
            int groupFormat = (int) group.stream().filter(RawCaseResult::formatCompliant).count();
            int groupCalls = group.stream().mapToInt(RawCaseResult::toolCallCount).sum();
            int groupFailures = group.stream().mapToInt(RawCaseResult::failureCount).sum();
            builder.append("| ").append(entry.getKey())
                    .append(" | ").append(group.size())
                    .append(" | ").append(percent(groupHit, group.size()))
                    .append(" | ").append(percent(groupFormat, group.size()))
                    .append(" | ").append(String.format("%.2f", (double) groupCalls / group.size()))
                    .append(" | ").append(groupFailures)
                    .append(" |\n");
        }

        builder.append("\n## 逐用例明细\n\n");
        builder.append("| ID | 类别 | 期望工具 | 实际调用 | 调用数 | 命中 | 格式 | 耗时 |\n");
        builder.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (RawCaseResult result : results) {
            EvalCase evalCase = new EvalCase(result.id(), result.category(), result.prompt(), List.of(), false);
            builder.append("| ").append(result.id())
                    .append(" | ").append(result.category())
                    .append(" | ").append(result.calledTools().isEmpty() ? "-" : String.join(",", result.calledTools()))
                    .append(" | ").append(result.toolCallCount())
                    .append(" | ").append(result.expectationSatisfied() ? "是" : "否")
                    .append(" | ").append(result.formatCompliant() ? "是" : "否")
                    .append(" | ").append(result.elapsedMillis()).append(" ms")
                    .append(" |\n");
            if (!result.error().isBlank()) {
                builder.append("| | | | | | | | 异常:").append(escape(result.error())).append(" |\n");
            }
        }
        return builder.toString();
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
            String id,
            String category,
            String prompt,
            List<String> calledTools,
            int toolCallCount,
            int executedCount,
            int cacheHitCount,
            int failureCount,
            int redundantCount,
            int redundantInvocationCount,
            boolean expectationSatisfied,
            boolean formatCompliant,
            long elapsedMillis,
            int answerLength,
            String error
    ) {
    }
}
