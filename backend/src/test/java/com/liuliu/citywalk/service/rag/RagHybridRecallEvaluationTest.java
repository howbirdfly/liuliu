package com.liuliu.citywalk.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.config.MilvusProperties;
import com.liuliu.citywalk.config.RagProperties;
import com.liuliu.citywalk.mapper.CommunityMapper;
import com.liuliu.citywalk.mapper.entity.CommunityWalkQueryRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RAG 召回评测:对比「纯向量」「向量 + 关键词 RRF 融合」「混合 + 规则精排」三路的 Recall@K 与 MRR。
 *
 * <p>默认跳过,需要显式打开(不依赖 Spring 上下文,手工装配依赖,使用独立的 eval collection):
 * <pre>
 * $env:RAG_EVAL='true'
 * $env:LIULIU_AI_EMBEDDING_API_KEY='...'
 * $env:DEEPSEEK_API_KEY='...'   # 不配则退化成本地模板生成 query
 * mvn test -Dtest=RagHybridRecallEvaluationTest
 * </pre>
 *
 * <p>可选环境变量:RAG_EVAL_LIMIT(默认 40)、RAG_EVAL_K(默认 5)、RAG_EVAL_QUERY_MODE(llm/template)、
 * RAG_EVAL_COLLECTION(默认 citywalk_knowledge_eval)、RAG_EVAL_DB_URL/USER/PASSWORD。
 */
@EnabledIfEnvironmentVariable(named = "RAG_EVAL", matches = "true")
class RagHybridRecallEvaluationTest {

    private static final int CANDIDATE_TOP_K = 20;
    private static final int MAX_RANK_FOR_MRR = 10;
    private static final String DEFAULT_DB_URL =
            "jdbc:mysql://127.0.0.1:3306/liuliu_citywalk?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";

    private static final String QUERY_GEN_INSTRUCTIONS = """
            你是 City Walk App 的用户意图改写器。下面会给你一条真实的漫步帖子信息,
            请把它改写成一条用户在 App 里输入的搜索需求。
            要求:
            1. 用自然口语,像用户提问,不要照抄标题原文,不要出现引号或书名号;
            2. 必须保留能让系统找到这条路线所需的 2~4 个关键信息(地点/主题/风格/时间等),但换一种说法;
            3. 只输出这一条需求,不要解释、不要分点,不超过 60 个字。
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void compareVectorOnlyAgainstHybridRecall() throws Exception {
        int walkLimit = intEnv("RAG_EVAL_LIMIT", 40);
        int recallK = intEnv("RAG_EVAL_K", 5);
        String queryMode = env("RAG_EVAL_QUERY_MODE", "llm").toLowerCase(Locale.ROOT);
        String embeddingApiKey = env("LIULIU_AI_EMBEDDING_API_KEY", env("RAG_EVAL_EMBEDDING_API_KEY", ""));
        if (embeddingApiKey.isBlank()) {
            throw new IllegalStateException("missing LIULIU_AI_EMBEDDING_API_KEY");
        }
        String chatApiKey = env("DEEPSEEK_API_KEY", env("RAG_EVAL_CHAT_API_KEY", ""));

        List<CommunityWalkQueryRow> walks = loadWalksFromMysql(walkLimit);
        assertFalse(walks.isEmpty(), "no public walks to evaluate");
        System.out.println("[rag-eval] corpus walks = " + walks.size());

        RagProperties ragProperties = new RagProperties();
        ragProperties.setHybridKeywordRecallEnabled(true);
        ragProperties.setHybridKeywordWeight(doubleEnv("RAG_EVAL_KEYWORD_WEIGHT", ragProperties.getHybridKeywordWeight()));

        MilvusProperties milvusProperties = new MilvusProperties();
        milvusProperties.setEnabled(true);
        milvusProperties.setUri(env("MILVUS_URI", "http://127.0.0.1:19530"));
        milvusProperties.setToken(env("MILVUS_TOKEN", "root:Milvus"));
        milvusProperties.setCollection(env("RAG_EVAL_COLLECTION", "citywalk_knowledge_eval"));
        milvusProperties.setDimension(intEnv("RAG_EVAL_DIMENSION", 1024));

        MilvusClientProvider milvusClientProvider = new MilvusClientProvider(milvusProperties);
        MilvusVectorStore vectorStore = new MilvusVectorStore(milvusClientProvider, objectMapper);
        EmbeddingModel embeddingModel = buildEmbeddingModel(embeddingApiKey);
        EmbeddingService embeddingService = buildEmbeddingService(embeddingModel);

        CommunityMapper communityMapper = buildInMemoryCommunityMapper(walks);
        CommunityWalkKeywordRecallService keywordRecallService =
                new CommunityWalkKeywordRecallService(communityMapper, ragProperties, objectMapper);
        DefaultKnowledgeRetrievalService retrievalService =
                new DefaultKnowledgeRetrievalService(vectorStore, keywordRecallService, ragProperties);
        RuleBasedKnowledgeReranker reranker = new RuleBasedKnowledgeReranker();
        KnowledgeIngestionService ingestionService = new DefaultKnowledgeIngestionService(vectorStore);
        SpringAiKnowledgeDocumentService documentService = new SpringAiKnowledgeDocumentService(
                mock(KnowledgeSearchService.class),
                ingestionService,
                embeddingService,
                new SpringAiDocumentMapper(),
                vectorStore
        );
        CommunityKnowledgeIngestionService communityIngestionService = new CommunityKnowledgeIngestionService(
                communityMapper,
                documentService,
                vectorStore,
                objectMapper,
                new KnowledgeTextChunker(ragProperties)
        );

        boolean skipIngest = Boolean.parseBoolean(env("RAG_EVAL_SKIP_INGEST", "false"));
        if (!skipIngest) {
            vectorStore.dropCollection();
            vectorStore.ensureCollection();
            for (CommunityWalkQueryRow walk : walks) {
                communityIngestionService.syncPublicWalkById(walk.getId());
            }
            System.out.println("[rag-eval] indexed " + walks.size() + " walks into " + milvusProperties.getCollection());
        } else {
            vectorStore.ensureCollection();
            System.out.println("[rag-eval] skipped ingest, reusing " + milvusProperties.getCollection());
        }

        Path queryCachePath = Path.of("target", "rag-eval-queries-" + queryMode + ".json");
        Map<String, String> cachedQueries = loadQueryCache(queryCachePath);
        List<EvalCase> cases = new ArrayList<>(walks.size());
        for (CommunityWalkQueryRow walk : walks) {
            String query = cachedQueries.getOrDefault(String.valueOf(walk.getId()), "");
            if (query.isBlank()) {
                query = switch (queryMode) {
                    case "short" -> buildShortQuery(walk);
                    case "template" -> buildTemplateQuery(walk);
                    default -> chatApiKey.isBlank() ? "" : generateQueryByLlm(chatApiKey, walk);
                };
            }
            if (query.isBlank()) {
                query = buildTemplateQuery(walk);
            }
            cases.add(new EvalCase(walk.getId(), walk.getThemeTitle(), query));
        }
        saveQueryCache(queryCachePath, cases);

        List<EvalResult> results = evaluate(ragProperties, retrievalService, reranker, embeddingService, cases);
        String report = renderReport(walks.size(), recallK, results);
        Path reportPath = Path.of("target", "rag-eval-report.md");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report);
        System.out.println("[rag-eval] report written to " + reportPath.toAbsolutePath());
        System.out.println(report);
        milvusClientProvider.close();
    }

    private List<EvalResult> evaluate(
            RagProperties ragProperties,
            DefaultKnowledgeRetrievalService retrievalService,
            RuleBasedKnowledgeReranker reranker,
            EmbeddingService embeddingService,
            List<EvalCase> cases
    ) {
        boolean hybridOriginallyEnabled = ragProperties.isHybridKeywordRecallEnabled();
        List<EvalResult> results = new ArrayList<>(cases.size());
        try {
            for (EvalCase evalCase : cases) {
                List<Float> embedding = embeddingService.embed(evalCase.query());
                VectorSearchQuery query = new VectorSearchQuery(
                        evalCase.query(),
                        embedding,
                        CANDIDATE_TOP_K,
                        Map.of("source_type", "community_walk")
                );

                ragProperties.setHybridKeywordRecallEnabled(false);
                List<KnowledgeHit> vectorOnly = retrievalService.retrieve(query);
                List<KnowledgeHit> vectorReranked = reranker.rerank(evalCase.query(), CANDIDATE_TOP_K, vectorOnly);

                ragProperties.setHybridKeywordRecallEnabled(true);
                List<KnowledgeHit> hybrid = retrievalService.retrieve(query);
                List<KnowledgeHit> hybridReranked = reranker.rerank(evalCase.query(), CANDIDATE_TOP_K, hybrid);

                results.add(new EvalResult(
                        evalCase,
                        rankOf(vectorOnly, evalCase.walkId()),
                        rankOf(vectorReranked, evalCase.walkId()),
                        rankOf(hybrid, evalCase.walkId()),
                        rankOf(hybridReranked, evalCase.walkId())
                ));
            }
        } finally {
            ragProperties.setHybridKeywordRecallEnabled(hybridOriginallyEnabled);
        }
        return results;
    }

    private List<CommunityWalkQueryRow> loadWalksFromMysql(int limit) throws Exception {
        String sql = """
                select wr.id, wr.theme_title, wr.theme_snapshot, wr.location_name, wr.missions_completed,
                       wr.note_text, wr.created_at, u.nickname,
                       (select group_concat(distinct wrt.tag_name order by wrt.tag_name separator '||')
                        from walk_record_tags wrt where wrt.walk_id = wr.id) as tags
                from walk_records wr
                left join users u on u.id = wr.user_id
                where wr.is_public = 1 and wr.status = 'active'
                order by wr.created_at desc
                limit ?
                """;
        String url = env("RAG_EVAL_DB_URL", DEFAULT_DB_URL);
        String user = env("RAG_EVAL_DB_USER", "root");
        String password = env("RAG_EVAL_DB_PASSWORD", "123456");

        List<CommunityWalkQueryRow> walks = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, user, password);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, Math.max(1, limit));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    CommunityWalkQueryRow row = new CommunityWalkQueryRow();
                    row.setId(resultSet.getLong("id"));
                    row.setThemeTitle(resultSet.getString("theme_title"));
                    row.setThemeSnapshot(resultSet.getString("theme_snapshot"));
                    row.setLocationName(resultSet.getString("location_name"));
                    row.setMissionsCompleted(resultSet.getString("missions_completed"));
                    row.setNoteText(resultSet.getString("note_text"));
                    Timestamp createdAt = resultSet.getTimestamp("created_at");
                    row.setCreatedAt(createdAt);
                    row.setAuthorNickname(resultSet.getString("nickname"));
                    row.setTags(resultSet.getString("tags"));
                    walks.add(row);
                }
            }
        }
        return walks;
    }

    /**
     * 用 Mockito 实现 CommunityMapper 的查询语义(等价于 CommunityMapper.xml 里的 LIKE + created_at desc),
     * 避免测试依赖 MyBatis/Spring 上下文。
     */
    private CommunityMapper buildInMemoryCommunityMapper(List<CommunityWalkQueryRow> walks) {
        CommunityMapper mapper = mock(CommunityMapper.class);
        when(mapper.listLatestPublicWalks(nullable(Long.class), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    int limit = invocation.getArgument(1);
                    int offset = invocation.getArgument(2);
                    return walks.stream().skip(offset).limit(limit).toList();
                });
        when(mapper.findPublicWalkById(nullable(Long.class), nullable(Long.class)))
                .thenAnswer(invocation -> {
                    Long walkId = invocation.getArgument(0);
                    return walks.stream().filter(walk -> walk.getId().equals(walkId)).findFirst().orElse(null);
                });
        when(mapper.searchPublicWalks(anyString(), nullable(Long.class), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    String keyword = invocation.getArgument(0);
                    int limit = invocation.getArgument(2);
                    int offset = invocation.getArgument(3);
                    String normalized = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
                    if (normalized.isBlank()) {
                        return List.of();
                    }
                    return walks.stream()
                            .filter(walk -> matchesKeyword(walk, normalized))
                            .skip(offset)
                            .limit(limit)
                            .toList();
                });
        return mapper;
    }

    private boolean matchesKeyword(CommunityWalkQueryRow walk, String keyword) {
        return contains(walk.getThemeTitle(), keyword)
                || contains(walk.getLocationName(), keyword)
                || contains(walk.getNoteText(), keyword)
                || contains(walk.getAuthorNickname(), keyword)
                || contains(walk.getTags(), keyword);
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private EmbeddingModel buildEmbeddingModel(String apiKey) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5000);
        requestFactory.setReadTimeout(15000);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(env("LIULIU_AI_EMBEDDING_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"))
                .embeddingsPath("/embeddings")
                .apiKey(apiKey)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(env("LIULIU_AI_EMBEDDING_MODEL", "text-embedding-v4"))
                .dimensions(intEnv("LIULIU_AI_EMBEDDING_DIMENSIONS", 1024))
                .encodingFormat("float")
                .build();
        return new OpenAiEmbeddingModel(openAiApi, MetadataMode.NONE, options);
    }

    private EmbeddingService buildEmbeddingService(EmbeddingModel embeddingModel) {
        return new EmbeddingService() {
            @Override
            public String provider() {
                return "rag_eval_embedding";
            }

            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public List<Float> embed(String text) {
                return toFloatList(embeddingModel.embed(text));
            }

            @Override
            public List<List<Float>> embedAll(List<String> texts) {
                if (texts == null || texts.isEmpty()) {
                    return List.of();
                }
                return embeddingModel.embed(texts).stream().map(this::toFloatList).toList();
            }

            private List<Float> toFloatList(float[] values) {
                List<Float> result = new ArrayList<>(values.length);
                for (float value : values) {
                    result.add(value);
                }
                return result;
            }
        };
    }

    private String generateQueryByLlm(String apiKey, CommunityWalkQueryRow walk) {
        try {
            String payload = QUERY_GEN_INSTRUCTIONS + "\n\n"
                    + "标题:" + safe(walk.getThemeTitle()) + "\n"
                    + "地点:" + safe(walk.getLocationName()) + "\n"
                    + "标签:" + safe(walk.getTags()) + "\n"
                    + "描述:" + safe(walk.getNoteText());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", env("RAG_EVAL_CHAT_MODEL", "deepseek-chat"));
            body.put("messages", List.of(Map.of("role", "user", "content", payload)));
            body.put("temperature", 0.3);
            body.put("max_tokens", 200);
            body.put("stream", false);

            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(env("RAG_EVAL_CHAT_BASE_URL", "https://api.deepseek.com") + "/chat/completions"))
                    .timeout(Duration.ofSeconds(45))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());
            return root.path("choices").path(0).path("message").path("content").asText("")
                    .replaceAll("\\s+", " ").trim();
        } catch (Exception error) {
            System.out.println("[rag-eval] llm query failed for walk " + walk.getId() + ": " + error.getMessage());
            return "";
        }
    }

    private String buildTemplateQuery(CommunityWalkQueryRow walk) {
        String location = safe(walk.getLocationName());
        String tags = safe(walk.getTags()).replace("||", "、");
        StringBuilder builder = new StringBuilder();
        if (!location.isBlank()) {
            builder.append("想去").append(location).append("附近走走,");
        }
        if (!tags.isBlank()) {
            builder.append("喜欢").append(tags).append("这种风格,");
        }
        builder.append("有没有类似的citywalk路线推荐");
        return builder.toString();
    }

    /**
     * 短查询模式:模拟用户在搜索框里输入的词组,例如 "中珠 散步 咖啡"。
     * 当地点存在常见简称时优先用简称,用于验证关键词 + 同义词通道是否真的能补上向量召回。
     */
    private String buildShortQuery(CommunityWalkQueryRow walk) {
        StringBuilder builder = new StringBuilder(aliasFor(safe(walk.getLocationName())));
        for (String tag : splitTags(walk.getTags())) {
            if (tag.isBlank() || builder.indexOf(tag) >= 0) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(tag);
            if (builder.length() >= 18) {
                break;
            }
        }
        String query = builder.toString().trim();
        return query.length() > 24 ? query.substring(0, 24) : query;
    }

    private String aliasFor(String location) {
        if (location == null || location.isBlank()) {
            return "";
        }
        Map<String, String> aliases = Map.of(
                "中山大学珠海校区", "中珠",
                "珠江新城", "珠江新城",
                "海珠国家湿地公园", "海珠湿地"
        );
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            if (location.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return location;
    }

    private List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String tag : tags.split("\\|\\|")) {
            String normalized = tag.trim();
            if (!normalized.isBlank()) {
                result.add(normalized);
            }
            if (result.size() >= 3) {
                break;
            }
        }
        return result;
    }

    private Map<String, String> loadQueryCache(Path path) {
        if (!Files.exists(path)) {
            return Map.of();
        }
        try {
            Map<String, String> cached = objectMapper.readValue(
                    Files.readString(path),
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class)
            );
            System.out.println("[rag-eval] loaded " + cached.size() + " cached queries from " + path);
            return cached;
        } catch (Exception error) {
            System.out.println("[rag-eval] query cache ignored: " + error.getMessage());
            return Map.of();
        }
    }

    private void saveQueryCache(Path path, List<EvalCase> cases) {
        try {
            Map<String, String> cache = new LinkedHashMap<>();
            for (EvalCase evalCase : cases) {
                cache.put(String.valueOf(evalCase.walkId()), evalCase.query());
            }
            Files.createDirectories(path.getParent());
            Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(cache));
        } catch (Exception error) {
            System.out.println("[rag-eval] query cache write failed: " + error.getMessage());
        }
    }

    private double doubleEnv(String name, double fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private int rankOf(List<KnowledgeHit> hits, Long walkId) {
        if (hits == null || hits.isEmpty()) {
            return -1;
        }
        Map<String, Integer> firstRankBySource = new LinkedHashMap<>();
        int rank = 0;
        for (KnowledgeHit hit : hits) {
            if (hit == null || hit.sourceId() == null || firstRankBySource.containsKey(hit.sourceId())) {
                continue;
            }
            rank++;
            firstRankBySource.put(hit.sourceId(), rank);
        }
        return firstRankBySource.getOrDefault(String.valueOf(walkId), -1);
    }

    private String renderReport(int corpusSize, int recallK, List<EvalResult> results) {
        StringBuilder builder = new StringBuilder();
        builder.append("# RAG 混合检索召回评测\n\n");
        builder.append("- 语料条数(公开 walk):").append(corpusSize).append('\n');
        builder.append("- 评测 query 数:").append(results.size()).append('\n');
        builder.append("- 候选池 topK:").append(CANDIDATE_TOP_K).append('\n');
        builder.append("- embedding:").append(env("LIULIU_AI_EMBEDDING_MODEL", "text-embedding-v4"))
                .append(" / ").append(env("LIULIU_AI_EMBEDDING_DIMENSIONS", "1024")).append(" 维\n");
        builder.append("- 评测集构造:每条 walk 由 LLM 改写成用户口吻 query(禁止照抄标题),原帖为唯一正确答案\n\n");

        builder.append("## 汇总\n\n");
        builder.append("| 配置 | Recall@1 | Recall@3 | Recall@").append(recallK).append(" | Recall@10 | MRR@10 |\n");
        builder.append("| --- | --- | --- | --- | --- | --- |\n");
        appendMetricRow(builder, "纯向量", results, recallK, Metric.VECTOR_ONLY);
        appendMetricRow(builder, "纯向量+精排", results, recallK, Metric.VECTOR_RERANK);
        appendMetricRow(builder, "向量+关键词(RRF)", results, recallK, Metric.HYBRID);
        appendMetricRow(builder, "混合+规则精排", results, recallK, Metric.HYBRID_RERANK);

        double vectorRecall = recallAt(results, recallK, Metric.VECTOR_ONLY);
        double hybridRecall = recallAt(results, recallK, Metric.HYBRID);
        builder.append('\n');
        if (vectorRecall > 0) {
            builder.append("相对提升(混合 vs 纯向量,Recall@").append(recallK).append("): ")
                    .append(formatPercent((hybridRecall - vectorRecall) / vectorRecall)).append('\n');
        } else {
            builder.append("混合 vs 纯向量:纯向量基线为 0,无法计算相对提升\n");
        }
        builder.append("MRR@10 提升(混合 vs 纯向量): ")
                .append(String.format("%+.3f", mrrAt(results, Metric.HYBRID) - mrrAt(results, Metric.VECTOR_ONLY)))
                .append('\n');
        builder.append("关键词通道权重:").append(env("RAG_EVAL_KEYWORD_WEIGHT", "0.4")).append('\n');
        builder.append("查询模式:").append(env("RAG_EVAL_QUERY_MODE", "llm")).append('\n');

        builder.append("\n## 逐条明细\n\n");
        builder.append("| # | query | 正确帖子 | 纯向量 rank | 混合 rank | 混合+精排 rank |\n");
        builder.append("| --- | --- | --- | --- | --- | --- |\n");
        for (int index = 0; index < results.size(); index++) {
            EvalResult result = results.get(index);
            builder.append("| ").append(index + 1)
                    .append(" | ").append(escape(result.evalCase().query()))
                    .append(" | ").append(escape(result.evalCase().title()))
                    .append(" | ").append(rankText(result.vectorOnlyRank()))
                    .append(" | ").append(rankText(result.hybridRank()))
                    .append(" | ").append(rankText(result.hybridRerankRank()))
                    .append(" |\n");
        }
        return builder.toString();
    }

    private void appendMetricRow(
            StringBuilder builder,
            String label,
            List<EvalResult> results,
            int recallK,
            Metric metric
    ) {
        builder.append("| ").append(label)
                .append(" | ").append(formatPercent(recallAt(results, 1, metric)))
                .append(" | ").append(formatPercent(recallAt(results, 3, metric)))
                .append(" | ").append(formatPercent(recallAt(results, recallK, metric)))
                .append(" | ").append(formatPercent(recallAt(results, 10, metric)))
                .append(" | ").append(String.format("%.3f", mrrAt(results, metric)))
                .append(" |\n");
    }

    private double recallAt(List<EvalResult> results, int k, Metric metric) {
        if (results.isEmpty()) {
            return 0D;
        }
        long hit = results.stream().filter(result -> {
            int rank = rankOf(result, metric);
            return rank > 0 && rank <= k;
        }).count();
        return (double) hit / results.size();
    }

    private double mrrAt(List<EvalResult> results, Metric metric) {
        if (results.isEmpty()) {
            return 0D;
        }
        double sum = 0D;
        for (EvalResult result : results) {
            int rank = rankOf(result, metric);
            if (rank > 0 && rank <= MAX_RANK_FOR_MRR) {
                sum += 1D / rank;
            }
        }
        return sum / results.size();
    }

    private int rankOf(EvalResult result, Metric metric) {
        return switch (metric) {
            case VECTOR_ONLY -> result.vectorOnlyRank();
            case VECTOR_RERANK -> result.vectorRerankRank();
            case HYBRID -> result.hybridRank();
            case HYBRID_RERANK -> result.hybridRerankRank();
        };
    }

    private String rankText(int rank) {
        return rank > 0 ? String.valueOf(rank) : "未命中";
    }

    private String formatPercent(double value) {
        return String.format("%.1f%%", value * 100D);
    }

    private String escape(String value) {
        return safe(value).replace("|", "\\|").replace("\n", " ");
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
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

    private enum Metric {
        VECTOR_ONLY,
        VECTOR_RERANK,
        HYBRID,
        HYBRID_RERANK
    }

    private record EvalCase(Long walkId, String title, String query) {
    }

    private record EvalResult(
            EvalCase evalCase,
            int vectorOnlyRank,
            int vectorRerankRank,
            int hybridRank,
            int hybridRerankRank
    ) {
    }
}
