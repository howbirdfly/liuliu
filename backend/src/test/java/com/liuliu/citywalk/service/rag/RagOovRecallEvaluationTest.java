package com.liuliu.citywalk.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.config.MilvusProperties;
import com.liuliu.citywalk.config.RagProperties;
import com.liuliu.citywalk.mapper.CommunityMapper;
import com.liuliu.citywalk.mapper.entity.CommunityWalkQueryRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OOV(冷门/新专名)召回评测。
 *
 * <p>动机:在真实语料上评测发现,text-embedding-v4 对「中珠 / 日月贝」这类已知别名理解得很好,
 * 关键词通道拿不到增益。但 embedding 模型对**没见过的冷门专名**只能给出泛化向量,
 * 而关键词通道可以精确命中——这才是混合检索真正该赢的场景。
 *
 * <p>做法:往评测库里注入若干带虚构地名的合成帖子(明确标注为合成数据),用
 * 「虚构地名 + 常见标签」构造短查询,对比纯向量与混合检索的 Recall@K / MRR。
 *
 * <pre>
 * $env:RAG_OOV_EVAL='true'
 * $env:LIULIU_AI_EMBEDDING_API_KEY='...'
 * mvn test -Dtest=RagOovRecallEvaluationTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "RAG_OOV_EVAL", matches = "true")
class RagOovRecallEvaluationTest {

    private static final int CANDIDATE_TOP_K = 20;
    private static final int MAX_RANK_FOR_MRR = 10;
    private static final String DEFAULT_DB_URL =
            "jdbc:mysql://127.0.0.1:3306/liuliu_citywalk?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";

    /** 合成帖子:虚构地名 + 常见标签(标签与真实语料高度重合,用来制造干扰)。 */
    private static final List<SyntheticWalk> SYNTHETIC_WALKS = List.of(
            new SyntheticWalk("汀澜桥", List.of("咖啡", "日落", "老街")),
            new SyntheticWalk("栖霞里", List.of("夜景", "拍照", "小洋楼")),
            new SyntheticWalk("浣月巷", List.of("咖啡", "书店", "安静")),
            new SyntheticWalk("枕河北岸步道", List.of("夜跑", "沿河", "安静")),
            new SyntheticWalk("拾萤坡", List.of("夜景", "约会", "小众")),
            new SyntheticWalk("望鲸台", List.of("海边", "日落", "拍照")),
            new SyntheticWalk("落霞码头", List.of("日落", "咖啡", "江边")),
            new SyntheticWalk("青苔巷", List.of("老街", "拍照", "文艺")),
            new SyntheticWalk("云脚村", List.of("自然", "亲子", "轻松")),
            new SyntheticWalk("星屿岛", List.of("海边", "骑行", "露营")),
            new SyntheticWalk("半山旧仓", List.of("文艺", "逛展", "咖啡")),
            new SyntheticWalk("白鹭汀", List.of("湿地", "观鸟", "自然"))
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void compareOovRecallBetweenVectorOnlyAndHybrid() throws Exception {
        String embeddingApiKey = env("LIULIU_AI_EMBEDDING_API_KEY", env("RAG_OOV_EMBEDDING_API_KEY", ""));
        if (embeddingApiKey.isBlank()) {
            throw new IllegalStateException("missing LIULIU_AI_EMBEDDING_API_KEY");
        }

        List<CommunityWalkQueryRow> walks = loadWalksFromMysql(intEnv("RAG_OOV_LIMIT", 150));
        assertFalse(walks.isEmpty(), "no public walks to evaluate");

        RagProperties ragProperties = new RagProperties();
        ragProperties.setHybridKeywordRecallEnabled(true);
        ragProperties.setHybridKeywordWeight(doubleEnv("RAG_OOV_KEYWORD_WEIGHT", 1.0D));

        MilvusProperties milvusProperties = new MilvusProperties();
        milvusProperties.setEnabled(true);
        milvusProperties.setUri(env("MILVUS_URI", "http://127.0.0.1:19530"));
        milvusProperties.setToken(env("MILVUS_TOKEN", "root:Milvus"));
        milvusProperties.setCollection(env("RAG_OOV_COLLECTION", "citywalk_knowledge_oov_eval"));
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

        if (!Boolean.parseBoolean(env("RAG_OOV_SKIP_INGEST", "false"))) {
            vectorStore.dropCollection();
            vectorStore.ensureCollection();
            for (CommunityWalkQueryRow walk : walks) {
                communityIngestionService.syncPublicWalkById(walk.getId());
            }
            for (SyntheticWalk syntheticWalk : SYNTHETIC_WALKS) {
                documentService.replaceBySource(
                        "community_walk",
                        syntheticSourceId(syntheticWalk),
                        List.of(toDocument(syntheticWalk))
                );
            }
            System.out.println("[rag-oov-eval] indexed " + walks.size() + " real walks + "
                    + SYNTHETIC_WALKS.size() + " synthetic walks");
        } else {
            vectorStore.ensureCollection();
            System.out.println("[rag-oov-eval] skipped ingest, reusing " + milvusProperties.getCollection());
        }

        List<OovResult> results = new ArrayList<>(SYNTHETIC_WALKS.size());
        for (SyntheticWalk syntheticWalk : SYNTHETIC_WALKS) {
            String query = buildQuery(syntheticWalk);
            List<Float> embedding = embeddingService.embed(query);
            VectorSearchQuery searchQuery = new VectorSearchQuery(
                    query,
                    embedding,
                    CANDIDATE_TOP_K,
                    Map.of("source_type", "community_walk")
            );

            ragProperties.setHybridKeywordRecallEnabled(false);
            List<KnowledgeHit> vectorOnly = retrievalService.retrieve(searchQuery);
            List<KnowledgeHit> vectorReranked = reranker.rerank(query, CANDIDATE_TOP_K, vectorOnly);

            ragProperties.setHybridKeywordRecallEnabled(true);
            List<KnowledgeHit> hybrid = retrievalService.retrieve(searchQuery);
            List<KnowledgeHit> hybridReranked = reranker.rerank(query, CANDIDATE_TOP_K, hybrid);

            String expectedSourceId = syntheticSourceId(syntheticWalk);
            results.add(new OovResult(
                    syntheticWalk,
                    query,
                    firstRelevantRank(vectorOnly, expectedSourceId),
                    firstRelevantRank(vectorReranked, expectedSourceId),
                    firstRelevantRank(hybrid, expectedSourceId),
                    firstRelevantRank(hybridReranked, expectedSourceId)
            ));
        }

        String report = renderReport(results);
        Path reportPath = Path.of("target", "rag-oov-eval-report.md");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report);
        System.out.println("[rag-oov-eval] report written to " + reportPath.toAbsolutePath());
        System.out.println(report);
        milvusClientProvider.close();
    }

    private Document toDocument(SyntheticWalk syntheticWalk) {
        String tags = String.join("||", syntheticWalk.tags());
        String text = "主题标题：" + syntheticWalk.name() + "傍晚散步实录：很出片\n\n"
                + "地点名称：" + syntheticWalk.name() + "\n\n"
                + "标签：" + String.join("、", syntheticWalk.tags()) + "\n\n"
                + "主题描述：一条围绕" + syntheticWalk.name() + "展开的 citywalk 路线，"
                + "适合慢慢走、顺便" + syntheticWalk.tags().get(0) + "。\n\n"
                + "漫步备注：" + syntheticWalk.name() + "傍晚光线最好，人不算多。";
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("chunk_id", syntheticSourceId(syntheticWalk) + ":0");
        metadata.put("source_id", syntheticSourceId(syntheticWalk));
        metadata.put("source_type", "community_walk");
        metadata.put("title", syntheticWalk.name() + "傍晚散步实录：很出片");
        metadata.put("location_name", syntheticWalk.name());
        metadata.put("tags", tags);
        metadata.put("created_at", Instant.now().toString());
        metadata.put("chunk_index", 0);
        metadata.put("chunk_count", 1);
        metadata.put("synthetic", true);
        return new Document(syntheticSourceId(syntheticWalk) + ":0", text, metadata);
    }

    /** 模拟用户在搜索框里输入:冷门地名 + 两个常见偏好词。 */
    private String buildQuery(SyntheticWalk syntheticWalk) {
        List<String> tags = syntheticWalk.tags();
        return syntheticWalk.name() + " " + tags.get(0) + " " + tags.get(1);
    }

    private String syntheticSourceId(SyntheticWalk syntheticWalk) {
        return "synthetic-" + syntheticWalk.name();
    }

    private int firstRelevantRank(List<KnowledgeHit> hits, String expectedSourceId) {
        if (hits == null || hits.isEmpty()) {
            return -1;
        }
        int rank = 0;
        for (KnowledgeHit hit : hits) {
            if (hit == null) {
                continue;
            }
            rank++;
            if (expectedSourceId.equals(hit.sourceId())) {
                return rank;
            }
        }
        return -1;
    }

    private String renderReport(List<OovResult> results) {
        StringBuilder builder = new StringBuilder();
        builder.append("# RAG 冷门专名(OOV)召回评测\n\n");
        builder.append("- 合成帖子数:").append(results.size()).append("(虚构地名,明确标注 synthetic\n");
        builder.append("- 语料:150 篇真实公开 walk + 合成帖子\n");
        builder.append("- query 形式:虚构地名 + 两个常见标签,例如 `汀澜桥 咖啡 日落`\n");
        builder.append("- 相关性判定:必须命中该虚构地名对应的合成帖子\n");
        builder.append("- embedding:").append(env("LIULIU_AI_EMBEDDING_MODEL", "text-embedding-v4")).append("\n\n");

        builder.append("## 汇总\n\n");
        builder.append("| 配置 | Recall@1 | Recall@3 | Recall@5 | MRR@10 |\n");
        builder.append("| --- | --- | --- | --- | --- |\n");
        appendMetricRow(builder, "纯向量", results, Metric.VECTOR_ONLY);
        appendMetricRow(builder, "纯向量+精排", results, Metric.VECTOR_RERANK);
        appendMetricRow(builder, "向量+关键词(RRF)", results, Metric.HYBRID);
        appendMetricRow(builder, "混合+精排(线上链路)", results, Metric.HYBRID_RERANK);

        double vectorRecall = recallAt(results, 5, Metric.VECTOR_ONLY);
        double hybridRecall = recallAt(results, 5, Metric.HYBRID);
        builder.append("\nRecall@5:纯向量 ").append(formatPercent(vectorRecall))
                .append(" → 混合 ").append(formatPercent(hybridRecall)).append("  相对提升: ");
        if (vectorRecall > 0) {
            builder.append(formatPercent((hybridRecall - vectorRecall) / vectorRecall));
        } else {
            builder.append("纯向量基线为 0(无法计算相对值,即混合从 0 补到了 ")
                    .append(formatPercent(hybridRecall)).append(")");
        }
        builder.append("\n\n## 逐条明细\n\n");
        builder.append("| # | query | 纯向量 | 纯向量+精排 | 混合 | 混合+精排 |\n");
        builder.append("| --- | --- | --- | --- | --- | --- |\n");
        for (int index = 0; index < results.size(); index++) {
            OovResult result = results.get(index);
            builder.append("| ").append(index + 1)
                    .append(" | ").append(escape(result.query()))
                    .append(" | ").append(rankText(result.vectorOnlyRank()))
                    .append(" | ").append(rankText(result.vectorRerankRank()))
                    .append(" | ").append(rankText(result.hybridRank()))
                    .append(" | ").append(rankText(result.hybridRerankRank()))
                    .append(" |\n");
        }
        return builder.toString();
    }

    private void appendMetricRow(StringBuilder builder, String label, List<OovResult> results, Metric metric) {
        builder.append("| ").append(label)
                .append(" | ").append(formatPercent(recallAt(results, 1, metric)))
                .append(" | ").append(formatPercent(recallAt(results, 3, metric)))
                .append(" | ").append(formatPercent(recallAt(results, 5, metric)))
                .append(" | ").append(String.format("%.3f", mrrAt(results, metric)))
                .append(" |\n");
    }

    private double recallAt(List<OovResult> results, int k, Metric metric) {
        if (results.isEmpty()) {
            return 0D;
        }
        long hit = results.stream().filter(result -> {
            int rank = rankOf(result, metric);
            return rank > 0 && rank <= k;
        }).count();
        return (double) hit / results.size();
    }

    private double mrrAt(List<OovResult> results, Metric metric) {
        if (results.isEmpty()) {
            return 0D;
        }
        double sum = 0D;
        for (OovResult result : results) {
            int rank = rankOf(result, metric);
            if (rank > 0 && rank <= MAX_RANK_FOR_MRR) {
                sum += 1D / rank;
            }
        }
        return sum / results.size();
    }

    private int rankOf(OovResult result, Metric metric) {
        return switch (metric) {
            case VECTOR_ONLY -> result.vectorOnlyRank();
            case VECTOR_RERANK -> result.vectorRerankRank();
            case HYBRID -> result.hybridRank();
            case HYBRID_RERANK -> result.hybridRerankRank();
        };
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
        String url = env("RAG_OOV_DB_URL", DEFAULT_DB_URL);
        String user = env("RAG_OOV_DB_USER", "root");
        String password = env("RAG_OOV_DB_PASSWORD", "123456");

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
                return "rag_oov_eval_embedding";
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

    private String rankText(int rank) {
        return rank > 0 ? String.valueOf(rank) : "未命中";
    }

    private String formatPercent(double value) {
        return String.format("%.1f%%", value * 100D);
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

    private enum Metric {
        VECTOR_ONLY,
        VECTOR_RERANK,
        HYBRID,
        HYBRID_RERANK
    }

    private record SyntheticWalk(String name, List<String> tags) {
    }

    private record OovResult(
            SyntheticWalk syntheticWalk,
            String query,
            int vectorOnlyRank,
            int vectorRerankRank,
            int hybridRank,
            int hybridRerankRank
    ) {
    }
}
