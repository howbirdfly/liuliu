package com.liuliu.citywalk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.model.dto.response.AgentChatResponse;
import com.liuliu.citywalk.service.agent.LlmMessage;
import com.liuliu.citywalk.service.agent.LlmResponse;
import com.liuliu.citywalk.service.agent.SpringAiLlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent 评测:工具调用正确性 + 引用一致性(幻觉率)+ 输出格式合规率。
 *
 * <p>默认跳过,需要真实调用大模型与本项目的工具:
 * <pre>
 * $env:AGENT_EVAL='true'
 * mvn test -Dtest=AgentToolUseEvaluationTest
 * </pre>
 *
 * <p>为了让评测可离线复跑:
 * <ul>
 *   <li>关闭 agent 记忆(Redis 不可达时使用 Noop 实现)</li>
 *   <li>知识库指向评测用的 Milvus collection</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "AGENT_EVAL", matches = "true")
@TestPropertySource(properties = {
        "liuliu.redis.agent-memory.enabled=false",
        "liuliu.redis.agent-tool-cache.enabled=false",
        "milvus.collection=citywalk_knowledge_eval"
})
class AgentToolUseEvaluationTest {

    private static final Long EVAL_USER_ID = 999_999L;
    private static final List<String> REQUIRED_SECTIONS = List.of(
            "推荐区域", "路线顺序", "依据来源", "不确定项", "实用提醒"
    );
    private static final Pattern PLACE_PATTERN = Pattern.compile(
            "[\\u4e00-\\u9fa5A-Za-z0-9]{2,8}(?:路|街|巷|桥|公园|广场|码头|岛|湖|山|寺|塔|湾|步道|商圈|园区|古镇|湿地|景区|外滩|校园)"
    );
    /** 地名里不该出现的虚词/动词/量词,命中这些字符的候选直接丢弃,避免把整句话当成地点。 */
    private static final Set<Character> PLACE_STOP_CHARS = Set.of(
            '的', '了', '在', '是', '和', '想', '建', '议', '适', '合', '如', '果', '以', '及', '可', '能',
            '需', '要', '就', '再', '把', '从', '到', '先', '都', '会', '我', '你', '他', '它', '这', '那',
            '并', '而', '但', '或', '与', '里', '个', '条', '段', '时', '长', '走', '逛', '拍', '沿', '约',
            '若', '只', '感', '受', '非', '常', '很', '比', '较', '另', '外', '还', '也', '有', '没', '每',
            '呢', '吗', '吧', '啊', '哦'
    );
    private static final Set<String> PLACE_STOP_WORDS = Set.of(
            "路线", "步道", "沿线", "周边", "附近", "城市散步", "散步路线", "推荐路线", "citywalk"
    );
    private static final String CITATION_JUDGE_INSTRUCTIONS = """
            你是 City Walk 助手的答案核查员,会拿到「工具检索结果」和「助手最终回答」。
            请找出回答里提到的、但工具结果中完全没有依据的**具体地点名**(例如:外滩、武康路、野狸岛)。
            要求:
            1. 只统计具体地点,不要统计"路线""步道""街区""老城"这类通用词或整句话;
            2. 若工具结果里出现过该地点,或明显是同一地点的别名/简称,视为有依据;
            3. 只输出 JSON,不要解释,格式为 {"claimed": 地点总数, "unsupported": ["地点1", "地点2"]},
               没有问题时 unsupported 返回空数组。
            """;

    private static final List<EvalCase> EVAL_CASES = List.of(
            new EvalCase("上海武康路半日", "帮我规划一条上海武康路的半日 citywalk 路线,喜欢拍照和咖啡",
                    Set.of("search_knowledge_base", "search_poi", "nearby_pois")),
            new EvalCase("中珠日落", "中珠附近有什么适合看日落的地方?",
                    Set.of("search_knowledge_base", "search_poi", "nearby_pois")),
            new EvalCase("望平街夜游", "成都望平街晚上适合散步吗?顺便推荐点咖啡馆",
                    Set.of("search_knowledge_base", "search_poi", "nearby_pois")),
            new EvalCase("永庆坊两小时", "我想在广州永庆坊随便逛逛,大概两小时",
                    Set.of("search_knowledge_base", "search_poi", "nearby_pois")),
            new EvalCase("闲聊不应调工具", "今天天气不错",
                    Set.of()),
            new EvalCase("情侣路夜景", "珠海情侣路附近有没有适合看夜景的路线?",
                    Set.of("search_knowledge_base", "search_poi", "nearby_pois"))
    );

    @Autowired
    private AgentExecutionPipelineService agentExecutionPipelineService;

    @Autowired
    private SpringAiLlmClient llmClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void evaluateToolUseAndCitationConsistency() throws Exception {
        List<CaseResult> results = new ArrayList<>(EVAL_CASES.size());
        for (EvalCase evalCase : EVAL_CASES) {
            results.add(runCase(evalCase));
        }
        String report = renderReport(results);
        Path reportPath = Path.of("target", "agent-eval-report.md");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report);
        System.out.println("[agent-eval] report written to " + reportPath.toAbsolutePath());
        System.out.println(report);
    }

    private CaseResult runCase(EvalCase evalCase) {
        List<AgentExecutionEvent> events = new ArrayList<>();
        String answer = "";
        Integer iterations = null;
        try {
            AgentChatResponse response = agentExecutionPipelineService.execute(
                    EVAL_USER_ID,
                    evalCase.prompt(),
                    null,
                    events::add
            );
            answer = response == null || response.answer() == null ? "" : response.answer();
            iterations = response == null ? null : response.iterations();
        } catch (Exception error) {
            System.out.println("[agent-eval] case failed: " + evalCase.name() + " -> " + error.getMessage());
        }

        List<ToolInvocation> invocations = new ArrayList<>();
        StringBuilder toolEvidence = new StringBuilder();
        for (AgentExecutionEvent event : events) {
            if ("tool_call".equals(event.type())) {
                invocations.add(new ToolInvocation(event.name(), safe(event.input()), null));
            } else if ("tool_result".equals(event.type())) {
                toolEvidence.append(safe(event.output())).append('\n');
                for (int index = invocations.size() - 1; index >= 0; index--) {
                    ToolInvocation invocation = invocations.get(index);
                    if (invocation.toolName().equals(event.name()) && invocation.code() == null) {
                        invocations.set(index, new ToolInvocation(
                                invocation.toolName(), invocation.arguments(), event.code()));
                        break;
                    }
                }
            }
        }

        Set<String> calledTools = new LinkedHashSet<>();
        invocations.forEach(invocation -> calledTools.add(invocation.toolName()));
        Set<String> expectedTools = evalCase.expectedTools();
        boolean expectationSatisfied = expectedTools.isEmpty()
                ? invocations.isEmpty()
                : !java.util.Collections.disjoint(calledTools, expectedTools);

        long failureCount = invocations.stream().filter(invocation -> invocation.code() != null).count();
        long redundantCount = countRedundant(invocations);
        // 闲聊类用例本来就不该输出规划格式,格式合规只对"需要规划"的用例做要求。
        boolean formatRequired = !expectedTools.isEmpty();
        boolean formatCompliant = !formatRequired || REQUIRED_SECTIONS.stream().allMatch(answer::contains);

        JudgeResult judgeResult = judgeUnsupportedPlaces(answer, toolEvidence.toString());
        List<String> unsupportedPlaces = judgeResult.unsupported();
        int claimedPlaceCount = judgeResult.claimedCount();
        double hallucinationRate = claimedPlaceCount <= 0
                ? 0D
                : (double) unsupportedPlaces.size() / claimedPlaceCount;
        boolean taskSuccess = expectationSatisfied && formatCompliant && unsupportedPlaces.isEmpty();

        return new CaseResult(
                evalCase,
                List.copyOf(calledTools),
                invocations.size(),
                (int) failureCount,
                (int) redundantCount,
                expectationSatisfied,
                formatCompliant,
                claimedPlaceCount,
                unsupportedPlaces,
                hallucinationRate,
                taskSuccess,
                answer,
                iterations
        );
    }

    private long countRedundant(List<ToolInvocation> invocations) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        long redundant = 0L;
        for (ToolInvocation invocation : invocations) {
            String key = invocation.toolName() + "|" + safe(invocation.arguments());
            int count = seen.merge(key, 1, Integer::sum);
            if (count > 1) {
                redundant++;
            }
        }
        return redundant;
    }

    private Set<String> extractPlaces(String answer) {
        Set<String> places = new LinkedHashSet<>();
        if (answer == null || answer.isBlank()) {
            return places;
        }
        Matcher matcher = PLACE_PATTERN.matcher(answer);
        while (matcher.find()) {
            String candidate = matcher.group().trim();
            if (candidate.length() < 3 || PLACE_STOP_WORDS.contains(candidate) || containsStopChar(candidate)) {
                continue;
            }
            places.add(candidate);
        }
        return places;
    }

    private boolean containsStopChar(String candidate) {
        for (int index = 0; index < candidate.length(); index++) {
            if (PLACE_STOP_CHARS.contains(candidate.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 引用一致性用 LLM-as-judge 判定:规则抽取无法区分"地名"和"含地名字后缀的短语",
     * 也无法判断别名/简称是否指向同一地点,所以改成让模型带着工具结果做核查,规则抽取只做兜底。
     */
    private JudgeResult judgeUnsupportedPlaces(String answer, String evidence) {
        if (answer == null || answer.isBlank()) {
            return new JudgeResult(List.of(), 0);
        }
        try {
            LlmResponse response = llmClient.createConstrainedResponse(
                    CITATION_JUDGE_INSTRUCTIONS,
                    List.of(LlmMessage.user(
                            "工具检索结果:\n" + limit(evidence, 6000)
                                    + "\n\n助手最终回答:\n" + answer)),
                    0.0,
                    400
            );
            JsonNode root = objectMapper.readTree(stripCodeFence(response == null ? "" : response.content()));
            List<String> unsupported = new ArrayList<>();
            for (JsonNode node : root.path("unsupported")) {
                String value = node.asText("").trim();
                if (!value.isBlank()) {
                    unsupported.add(value);
                }
            }
            int claimed = root.path("claimed").asInt(0);
            return new JudgeResult(unsupported, Math.max(claimed, unsupported.size()));
        } catch (Exception error) {
            System.out.println("[agent-eval] citation judge failed, fallback to rule: " + error.getMessage());
            return fallbackJudge(answer, evidence);
        }
    }

    private JudgeResult fallbackJudge(String answer, String evidence) {
        Set<String> mentionedPlaces = extractPlaces(answer);
        List<String> unsupported = mentionedPlaces.stream()
                .filter(place -> !evidence.contains(place))
                .toList();
        return new JudgeResult(unsupported, mentionedPlaces.size());
    }

    private String stripCodeFence(String content) {
        String normalized = content == null ? "" : content.trim();
        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return normalized.substring(start, end + 1);
        }
        return normalized;
    }

    private String limit(String value, int maxLength) {
        String normalized = value == null ? "" : value;
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }

    private String renderReport(List<CaseResult> results) {
        int total = results.size();
        int expectationHit = (int) results.stream().filter(CaseResult::expectationSatisfied).count();
        int formatHit = (int) results.stream().filter(CaseResult::formatCompliant).count();
        int success = (int) results.stream().filter(CaseResult::taskSuccess).count();
        int totalCalls = results.stream().mapToInt(CaseResult::toolCallCount).sum();
        int totalFailures = results.stream().mapToInt(CaseResult::toolFailureCount).sum();
        int totalRedundant = results.stream().mapToInt(CaseResult::redundantCallCount).sum();
        int totalPlaces = results.stream().mapToInt(CaseResult::claimedPlaceCount).sum();
        int totalUnsupported = results.stream().mapToInt(result -> result.unsupportedPlaces().size()).sum();

        StringBuilder builder = new StringBuilder();
        builder.append("# Agent 工具调用与引用一致性评测\n\n");
        builder.append("- 用例数:").append(total).append("\n");
        builder.append("- 模型:deepseek-chat(经 Spring AI)\n");
        builder.append("- 记忆:关闭(Noop),知识库:citywalk_knowledge_eval\n\n");

        builder.append("## 汇总\n\n");
        builder.append("| 指标 | 数值 |\n");
        builder.append("| --- | --- |\n");
        builder.append("| 任务成功率 | ").append(percent(success, total)).append(" |\n");
        builder.append("| 工具期望命中率 | ").append(percent(expectationHit, total)).append(" |\n");
        builder.append("| 输出格式合规率 | ").append(percent(formatHit, total)).append(" |\n");
        builder.append("| 工具调用总数 | ").append(totalCalls).append(" |\n");
        builder.append("| 平均工具调用数/用例 | ")
                .append(String.format("%.2f", total == 0 ? 0D : (double) totalCalls / total)).append(" |\n");
        builder.append("| 工具失败率 | ").append(percent(totalFailures, totalCalls)).append(" |\n");
        builder.append("| 冗余调用率 | ").append(percent(totalRedundant, totalCalls)).append(" |\n");
        builder.append("| 答案中提及地点数 | ").append(totalPlaces).append(" |\n");
        builder.append("| 无工具证据支撑的地点(疑似编造) | ").append(totalUnsupported).append(" |\n");
        builder.append("| 幻觉率(无证据地点/提及地点) | ").append(percent(totalUnsupported, totalPlaces)).append(" |\n");

        builder.append("\n## 逐用例明细\n\n");
        builder.append("| # | 用例 | 期望工具 | 实际调用 | 调用数 | 命中期望 | 格式合规 | 疑似编造地点 | 单例成功 |\n");
        builder.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (int index = 0; index < results.size(); index++) {
            CaseResult result = results.get(index);
            builder.append("| ").append(index + 1)
                    .append(" | ").append(escape(result.evalCase().name()))
                    .append(" | ").append(result.evalCase().expectedTools().isEmpty()
                            ? "不应调用" : String.join(",", result.evalCase().expectedTools()))
                    .append(" | ").append(result.calledTools().isEmpty()
                            ? "-" : String.join(",", result.calledTools()))
                    .append(" | ").append(result.toolCallCount())
                    .append(" | ").append(result.expectationSatisfied() ? "是" : "否")
                    .append(" | ").append(result.formatCompliant() ? "是" : "否")
                    .append(" | ").append(result.unsupportedPlaces().isEmpty()
                            ? "-" : String.join("、", result.unsupportedPlaces()))
                    .append(" | ").append(result.taskSuccess() ? "是" : "否")
                    .append(" |\n");
        }

        builder.append("\n## 判定说明\n\n");
        builder.append("- **工具期望命中**:用例声明了应调用的工具集合,只要实际调用命中其中之一即算命中;\n");
        builder.append("  标注为\"不应调用\"的闲聊用例,要求一次工具都不调。\n");
        builder.append("- **引用一致性**:从答案中用规则抽取地点类实体(含 路/街/公园/广场/外滩 等后缀),\n");
        builder.append("  若该地点没有出现在任何工具返回结果里,记为\"疑似编造\"(可能误报,需人工复核)。\n");
        builder.append("- **任务成功**:期望命中 + 格式合规 + 无疑似编造地点。\n");
        return builder.toString();
    }

    private String percent(int numerator, int denominator) {
        if (denominator <= 0) {
            return "0.0%";
        }
        return String.format("%.1f%%", (double) numerator / denominator * 100D);
    }

    private String escape(String value) {
        return safe(value).replace("|", "\\|").replace("\n", " ");
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private record EvalCase(String name, String prompt, Set<String> expectedTools) {
    }

    private record ToolInvocation(String toolName, String arguments, String code) {
    }

    private record JudgeResult(List<String> unsupported, int claimedCount) {
    }

    private record CaseResult(
            EvalCase evalCase,
            List<String> calledTools,
            int toolCallCount,
            int toolFailureCount,
            int redundantCallCount,
            boolean expectationSatisfied,
            boolean formatCompliant,
            int claimedPlaceCount,
            List<String> unsupportedPlaces,
            double hallucinationRate,
            boolean taskSuccess,
            String answer,
            Integer iterations
    ) {
    }
}
