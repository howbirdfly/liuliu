package com.liuliu.citywalk.service.rag;

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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
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
 * 别名 / 缩写下标评测:验证"关键词 + 同义词扩展"通道是否真的补上了向量召回的短板。
 *
 * <p>相关性判定用的是「地点组」:例如 query {@code 中珠 校园散步} 的期望地点是 {@code 中山大学珠海校区},
 * 只要召回结果里出现该地点的任意一篇帖子就算命中。这样判定比"必须命中被改写的那一篇原文"更符合真实检索语义,
 * 也避免了同地点几十篇帖子导致的标注歧义。
 *
 * <p>默认跳过,需要显式打开:
 * <pre>
 * $env:RAG_ALIAS_EVAL='true'
 * $env:LIULIU_AI_EMBEDDING_API_KEY='...'
 * mvn test -Dtest=RagAliasRecallEvaluationTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "RAG_ALIAS_EVAL", matches = "true")
class RagAliasRecallEvaluationTest {

    private static final int CANDIDATE_TOP_K = 20;
    private static final int MAX_RANK_FOR_MRR = 10;
    private static final String DEFAULT_DB_URL =
            "jdbc:mysql://127.0.0.1:3306/liuliu_citywalk?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";

    /** 别名/缩写查询集:query → 期望地点(库里真实存在的地点名片段)。 */
    private static final List<AliasQuery> ALIAS_QUERIES = List.of(
            new AliasQuery("中珠 校园散步", "中山大学珠海校区"),
            new AliasQuery("中大珠海 食堂路线", "中山大学珠海校区"),
            new AliasQuery("中珠 日落", "中山大学珠海校区"),
            new AliasQuery("海珠湿地 发呆路线", "海珠国家湿地公园"),
            new AliasQuery("海珠湿地 自然散步", "海珠国家湿地公园"),
            new AliasQuery("珠城 夜景 散步", "珠江新城"),
            new AliasQuery("花城广场 约会 夜景", "珠江新城花城广场"),
            new AliasQuery("日月贝 海钓 出海", "珠海日月贝游艇海钓出海中心"),
            new AliasQuery("鸡山市集 咖啡", "下一站咖啡"),
            new AliasQuery("深圳行政服务大厅 散步", "深圳市行政服务大厅"),
            new AliasQuery("上海外滩 夜景", "外滩"),
            new AliasQuery("上海外滩 咖啡 散步", "外滩"),
            new AliasQuery("广州永庆坊 老街拍照", "永庆坊"),
            new AliasQuery("广州永庆坊 骑楼", "永庆坊"),
            new AliasQuery("广州东山口 小洋楼 咖啡", "东山口"),
            new AliasQuery("成都望平街 夜游", "望平街"),
            new AliasQuery("成都望平街 沿河步道", "望平街"),
            new AliasQuery("马山山庄 散步", "马山山庄"),
            new AliasQuery("高校科技园区 散步", "高校科技园区"),
            new AliasQuery("红花山郊野公园 自然散步", "红花山郊野公园")
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void compareAliasRecallBetweenVectorOnlyAndHybrid() throws Exception {
        String embeddingApiKey = env("LIULIU_AI_EMBEDDING_API_KEY", env("RAG_ALIAS_EMBEDDING_API_KEY", ""));
        if (embeddingApiKey.isBlank()) {
            throw new IllegalStateException("missing LIULIU_AI_EMBEDDING_API_KEY");
        }

        int walkLimit = intEnv("RAG_ALIAS_LIMIT", 150);
        List<CommunityWalkQueryRow> walks = loadWalksFromMysql(walkLimit);
        assertFalse(walks.isEmpty(), "no public walks to evaluate");

        RagProperties ragProperties = new RagProperties();
        ragProperties.setHybridKeywordRecallEnabled(true);
        ragProperties.setHybridKeywordWeight(doubleEnv("RAG_ALIAS_KEYWORD_WEIGHT", 1.0D));

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

        if (!Boolean.parseBoolean(env("RAG_ALIAS_SKIP_INGEST", "false"))) {
            vectorStore.dropCollection();
            vectorStore.ensureCollection();
            for (CommunityWalkQueryRow walk : walks) {
                communityIngestionService.syncPublicWalkById(walk.getId());
            }
            System.out.println("[rag-alias-eval] indexed " + walks.size() + " walks");
        } else {
            vectorStore.ensureCollection();
            System.out.println("[rag-alias-eval] skipped ingest, reusing " + milvusProperties.getCollection());
        }

        List<AliasResult> results = new ArrayList<>(ALIAS_QUERIES.size());
        for (AliasQuery aliasQuery : ALIAS_QUERIES) {
            List<Float> embedding = embeddingService.embed(aliasQuery.query());
            VectorSearchQuery query = new VectorSearchQuery(
                    aliasQuery.query(),
                    embedding,
                    CANDIDATE_TOP_K,
                    Map.of("source_type", "community_walk")
            );

            ragProperties.setHybridKeywordRecallEnabled(false);
            List<KnowledgeHit> vectorOnly = retrievalService.retrieve(query);
            List<KnowledgeHit> vectorReranked = reranker.rerank(aliasQuery.query(), CANDIDATE_TOP_K, vectorOnly);

            ragProperties.setHybridKeywordRecallEnabled(true);
            List<KnowledgeHit> hybrid = retrievalService.retrieve(query);
            List<KnowledgeHit> hybridReranked = reranker.rerank(aliasQuery.query(), CANDIDATE_TOP_K, hybrid);

            results.add(new AliasResult(
                    aliasQuery,
                    firstRelevantRank(vectorOnly, aliasQuery.expectedLocation()),
                    firstRelevantRank(vectorReranked, aliasQuery.expectedLocation()),
                    firstRelevantRank(hybrid, aliasQuery.expectedLocation()),
                    firstRelevantRank(hybridReranked, aliasQuery.expectedLocation())
            ));
        }

        String report = renderReport(results);
        Path reportPath = Path.of("target", "rag-alias-eval-report.md");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report);
        System.out.println("[rag-alias-eval] report written to " + reportPath.toAbsolutePath());
        System.out.println(report);
        milvusClientProvider.close();
    }

    private int firstRelevantRank(List<KnowledgeHit> hits, String expectedLocation) {
        if (hits == null || hits.isEmpty()) {
            return -1;
        }
        int rank = 0;
        for (KnowledgeHit hit : hits) {
            if (hit == null) {
                continue;
            }
            rank++;
            if (isRelevant(hit, expectedLocation)) {
                return rank;
            }
        }
        return -1;
    }

    private boolean isRelevant(KnowledgeHit hit, String expectedLocation) {
        if (expectedLocation == null || expectedLocation.isBlank()) {
            return false;
        }
        Object metadataLocation = hit.metadata() == null ? null : hit.metadata().get("location_name");
        String location = metadataLocation == null ? "" : String.valueOf(metadataLocation);
        return location.contains(expectedLocation)
                || safe(hit.title()).contains(expectedLocation)
                || safe(hit.content()).contains(expectedLocation);
    }

    private String renderReport(List<AliasResult> results) {
        StringBuilder builder = new StringBuilder();
        builder.append("# RAG 别名/缩写召回评测\n\n");
        builder.append("- query 数:").append(results.size()).append('\n');
        builder.append("- 相关性判定:召回结果命中「期望地点」的任意一篇帖子即算命中\n");
        builder.append("- embedding:").append(env("LIULIU_AI_EMBEDDING_MODEL", "text-embedding-v4")).append('\n');
        builder.append("- 关键词权重:").append(env("RAG_ALIAS_KEYWORD_WEIGHT", "1.0")).append("\n\n");

        builder.append("## 汇总\n\n");
        builder.append("| 配置 | Recall@1 | Recall@3 | Recall@5 | MRR@10 |\n");
        builder.append("| --- | --- | --- | --- | --- |\n");
        appendMetricRow(builder, "纯向量", results, Metric.VECTOR_ONLY);
        appendMetricRow(builder, "纯向量+精排", results, Metric.VECTOR_RERANK);
        appendMetricRow(builder, "向量+关键词(RRF)", results, Metric.HYBRID);
        appendMetricRow(builder, "混合+精排(线上链路)", results, Metric.HYBRID_RERANK);

        double vectorRecall = recallAt(results, 5, Metric.VECTOR_ONLY);
        double hybridRecall = recallAt(results, 5, Metric.HYBRID);
        builder.append("\n混合 vs 纯向量,Recall@5 相对提升: ");
        if (vectorRecall > 0) {
            builder.append(formatPercent((hybridRecall - vectorRecall) / vectorRecall));
        } else {
            builder.append("纯向量基线为 0");
        }
        builder.append("\nMRR@10 变化(混合 vs 纯向量): ")
                .append(String.format("%+.3f", mrrAt(results, Metric.HYBRID) - mrrAt(results, Metric.VECTOR_ONLY)))
                .append("\n\n");

        builder.append("## 逐条明细\n\n");
        builder.append("| # | query | 期望地点 | 纯向量 | 纯向量+精排 | 混合 | 混合+精排 |\n");
        builder.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (int index = 0; index < results.size(); index++) {
            AliasResult result = results.get(index);
            builder.append("| ").append(index + 1)
                    .append(" | ").append(escape(result.query().query()))
                    .append(" | ").append(escape(result.query().expectedLocation()))
                    .append(" | ").append(rankText(result.vectorOnlyRank()))
                    .append(" | ").append(rankText(result.vectorRerankRank()))
                    .append(" | ").append(rankText(result.hybridRank()))
                    .append(" | ").append(rankText(result.hybridRerankRank()))
                    .append(" |\n");
        }
        return builder.toString();
    }

    private void appendMetricRow(StringBuilder builder, String label, List<AliasResult> results, Metric metric) {
        builder.append("| ").append(label)
                .append(" | ").append(formatPercent(recallAt(results, 1, metric)))
                .append(" | ").append(formatPercent(recallAt(results, 3, metric)))
                .append(" | ").append(formatPercent(recallAt(results, 5, metric)))
                .append(" | ").append(String.format("%.3f", mrrAt(results, metric)))
                .append(" |\n");
    }

    private double recallAt(List<AliasResult> results, int k, Metric metric) {
        if (results.isEmpty()) {
            return 0D;
        }
        long hit = results.stream().filter(result -> {
            int rank = rankOf(result, metric);
            return rank > 0 && rank <= k;
        }).count();
        return (double) hit / results.size();
    }

    private double mrrAt(List<AliasResult> results, Metric metric) {
        if (results.isEmpty()) {
            return 0D;
        }
        double sum = 0D;
        for (AliasResult result : results) {
            int rank = rankOf(result, metric);
            if (rank > 0 && rank <= MAX_RANK_FOR_MRR) {
                sum += 1D / rank;
            }
        }
        return sum / results.size();
    }

    private int rankOf(AliasResult result, Metric metric) {
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
        String url = env("RAG_ALIAS_DB_URL", DEFAULT_DB_URL);
        String user = env("RAG_ALIAS_DB_USER", "root");
        String password = env("RAG_ALIAS_DB_PASSWORD", "123456");

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
                return "rag_alias_eval_embedding";
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

    private record AliasQuery(String query, String expectedLocation) {
    }

    private record AliasResult(
            AliasQuery query,
            int vectorOnlyRank,
            int vectorRerankRank,
            int hybridRank,
            int hybridRerankRank
    ) {
    }
}
