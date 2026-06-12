package com.liuliu.citywalk.service.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.liuliu.citywalk.config.MilvusProperties;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddCollectionFieldReq;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.utility.response.CheckHealthResp;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class MilvusVectorStore implements VectorStore {

    private static final int MAX_ID_LENGTH = 128;
    private static final int MAX_SOURCE_ID_LENGTH = 128;
    private static final int MAX_SOURCE_TYPE_LENGTH = 64;
    private static final int MAX_TITLE_LENGTH = 512;
    private static final int MAX_CONTENT_LENGTH = 8192;

    private final MilvusClientProvider milvusClientProvider;
    private final ObjectMapper objectMapper;
    private final Gson gson = new Gson();

    public MilvusVectorStore(MilvusClientProvider milvusClientProvider, ObjectMapper objectMapper) {
        this.milvusClientProvider = milvusClientProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public String provider() {
        return "milvus";
    }

    @Override
    public boolean isEnabled() {
        return milvusClientProvider.isEnabled();
    }

    @Override
    public VectorStoreHealth health() {
        if (!isEnabled()) {
            return new VectorStoreHealth(false, false, provider(), List.of("milvus_disabled"));
        }
        try {
            CheckHealthResp response = milvusClientProvider.getClient().checkHealth();
            return new VectorStoreHealth(
                    true,
                    Boolean.TRUE.equals(response.getIsHealthy()),
                    provider(),
                    response.getReasons() == null ? List.of() : response.getReasons()
            );
        } catch (Exception error) {
            return new VectorStoreHealth(true, false, provider(), List.of(error.getMessage() == null ? "milvus_unreachable" : error.getMessage()));
        }
    }

    @Override
    public void ensureCollection() {
        MilvusProperties properties = milvusClientProvider.getProperties();
        if (!isEnabled()) {
            throw new IllegalStateException("milvus_disabled");
        }
        if (properties.getCollection() == null || properties.getCollection().isBlank()) {
            throw new IllegalStateException("milvus_collection_required");
        }

        boolean exists = milvusClientProvider.getClient().hasCollection(HasCollectionReq.builder()
                .collectionName(properties.getCollection())
                .build());
        if (exists) {
            ensureMetadataField(properties);
            return;
        }

        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();
        schema.addField(AddFieldReq.builder()
                .fieldName(properties.getChunkIdField())
                .dataType(DataType.VarChar)
                .isPrimaryKey(true)
                .autoID(false)
                .maxLength(MAX_ID_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(properties.getSourceIdField())
                .dataType(DataType.VarChar)
                .maxLength(MAX_SOURCE_ID_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(properties.getSourceTypeField())
                .dataType(DataType.VarChar)
                .maxLength(MAX_SOURCE_TYPE_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(properties.getTitleField())
                .dataType(DataType.VarChar)
                .maxLength(MAX_TITLE_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(properties.getContentField())
                .dataType(DataType.VarChar)
                .maxLength(MAX_CONTENT_LENGTH)
                .enableAnalyzer(true)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(properties.getVectorField())
                .dataType(DataType.FloatVector)
                .dimension(properties.getDimension())
                .build());
        schema.addField(buildMetadataField(properties));

        List<IndexParam> indexParams = List.of(
                IndexParam.builder()
                        .fieldName(properties.getVectorField())
                        .indexType(IndexParam.IndexType.AUTOINDEX)
                        .metricType(IndexParam.MetricType.COSINE)
                        .build()
        );

        milvusClientProvider.getClient().createCollection(CreateCollectionReq.builder()
                .collectionName(properties.getCollection())
                .collectionSchema(schema)
                .indexParams(indexParams)
                .build());
    }

    @Override
    public void upsertDocuments(List<KnowledgeDocument> documents) {
        ensureCollection();
        if (documents == null || documents.isEmpty()) {
            return;
        }

        MilvusProperties properties = milvusClientProvider.getProperties();
        List<JsonObject> rows = new ArrayList<>();
        for (KnowledgeDocument document : documents) {
            if (document == null || document.embedding() == null || document.embedding().isEmpty()) {
                continue;
            }
            JsonObject row = new JsonObject();
            row.addProperty(properties.getChunkIdField(), truncate(document.chunkId(), MAX_ID_LENGTH));
            row.addProperty(properties.getSourceIdField(), truncate(document.sourceId(), MAX_SOURCE_ID_LENGTH));
            row.addProperty(properties.getSourceTypeField(), truncate(document.sourceType(), MAX_SOURCE_TYPE_LENGTH));
            row.addProperty(properties.getTitleField(), truncate(document.title(), MAX_TITLE_LENGTH));
            row.addProperty(properties.getContentField(), truncate(document.content(), MAX_CONTENT_LENGTH));
            row.add(properties.getVectorField(), gson.toJsonTree(document.embedding()));
            row.add(properties.getMetadataField(), gson.toJsonTree(normalizeMetadata(document.metadata())));
            rows.add(row);
        }

        if (rows.isEmpty()) {
            return;
        }

        milvusClientProvider.getClient().upsert(UpsertReq.builder()
                .collectionName(properties.getCollection())
                .data(rows)
                .build());
    }

    @Override
    public List<KnowledgeHit> search(VectorSearchQuery query) {
        ensureCollection();
        if (query == null || query.embedding() == null || query.embedding().isEmpty()) {
            return List.of();
        }

        MilvusProperties properties = milvusClientProvider.getProperties();
        int topK = query.topK() <= 0 ? Math.max(1, properties.getDefaultTopK()) : query.topK();

        SearchResp searchResponse = milvusClientProvider.getClient().search(SearchReq.builder()
                .collectionName(properties.getCollection())
                .data(List.of(new FloatVec(query.embedding())))
                .topK(topK)
                .filter(buildFilterExpression(query.filters()))
                .outputFields(List.of(
                        properties.getChunkIdField(),
                        properties.getSourceIdField(),
                        properties.getSourceTypeField(),
                        properties.getTitleField(),
                        properties.getContentField(),
                        properties.getMetadataField()
                ))
                .build());

        List<KnowledgeHit> hits = new ArrayList<>();
        if (searchResponse == null || searchResponse.getSearchResults() == null) {
            return hits;
        }

        for (List<SearchResp.SearchResult> batch : searchResponse.getSearchResults()) {
            if (batch == null) {
                continue;
            }
            for (SearchResp.SearchResult item : batch) {
                Map<String, Object> entity = convertEntityMap(item.getEntity());
                Map<String, Object> metadata = extractMetadata(entity.get(properties.getMetadataField()));
                hits.add(new KnowledgeHit(
                        defaultText(entity.get(properties.getChunkIdField())),
                        defaultText(entity.get(properties.getSourceIdField())),
                        defaultText(entity.get(properties.getSourceTypeField())),
                        defaultText(entity.get(properties.getTitleField())),
                        defaultText(entity.get(properties.getContentField())),
                        item.getScore(),
                        metadata
                ));
            }
        }
        return hits;
    }

    @Override
    public void deleteBySource(String sourceType, String sourceId) {
        ensureCollection();
        String filter = buildFilterExpression(Map.of(
                "source_type", sourceType,
                "source_id", sourceId
        ));
        if (filter.isBlank()) {
            return;
        }
        milvusClientProvider.getClient().delete(DeleteReq.builder()
                .collectionName(milvusClientProvider.getProperties().getCollection())
                .filter(filter)
                .build());
    }

    public void dropCollection() {
        if (!isEnabled()) {
            return;
        }
        MilvusProperties properties = milvusClientProvider.getProperties();
        boolean exists = milvusClientProvider.getClient().hasCollection(HasCollectionReq.builder()
                .collectionName(properties.getCollection())
                .build());
        if (!exists) {
            return;
        }
        milvusClientProvider.getClient().dropCollection(DropCollectionReq.builder()
                .collectionName(properties.getCollection())
                .build());
    }

    private String buildFilterExpression(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return "";
        }
        MilvusProperties properties = milvusClientProvider.getProperties();
        List<String> expressions = new ArrayList<>();
        appendFilter(expressions, properties.getSourceTypeField(), filters.get("source_type"));
        appendFilter(expressions, properties.getSourceIdField(), filters.get("source_id"));
        return String.join(" and ", expressions);
    }

    private void appendFilter(List<String> expressions, String fieldName, Object value) {
        if (value == null) {
            return;
        }
        String normalized = value.toString().trim();
        if (normalized.isBlank()) {
            return;
        }
        expressions.add(fieldName + " == \"" + normalized.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
    }

    private Map<String, Object> convertEntityMap(Object entity) {
        Object normalizedEntity = unwrapJsonValue(entity);
        if (normalizedEntity == null) {
            return Map.of();
        }
        if (normalizedEntity instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                converted.put(entry.getKey().toString(), entry.getValue());
            }
            return converted;
        }
        return objectMapper.convertValue(normalizedEntity, new TypeReference<>() {
        });
    }

    private void ensureMetadataField(MilvusProperties properties) {
        String metadataField = properties.getMetadataField();
        if (metadataField == null || metadataField.isBlank()) {
            return;
        }

        List<String> fieldNames = milvusClientProvider.getClient().describeCollection(DescribeCollectionReq.builder()
                        .collectionName(properties.getCollection())
                        .build())
                .getFieldNames();
        if (fieldNames != null && fieldNames.contains(metadataField)) {
            return;
        }

        AddCollectionFieldReq request = AddCollectionFieldReq.builder()
                .databaseName(properties.getDatabase())
                .collectionName(properties.getCollection())
                .build();
        request.setFieldName(metadataField);
        request.setDataType(DataType.JSON);
        request.setIsNullable(true);
        milvusClientProvider.getClient().addCollectionField(request);
    }

    private AddFieldReq buildMetadataField(MilvusProperties properties) {
        return AddFieldReq.builder()
                .fieldName(properties.getMetadataField())
                .dataType(DataType.JSON)
                .isNullable(true)
                .build();
    }

    private Map<String, Object> extractMetadata(Object metadataValue) {
        Object normalizedMetadata = unwrapJsonValue(metadataValue);
        if (normalizedMetadata == null) {
            return Map.of();
        }
        if (normalizedMetadata instanceof Map<?, ?>) {
            return convertEntityMap(normalizedMetadata);
        }
        if (normalizedMetadata instanceof String text) {
            try {
                return objectMapper.readValue(text, new TypeReference<>() {
                });
            } catch (Exception ignored) {
                return Map.of("raw", text);
            }
        }
        return objectMapper.convertValue(normalizedMetadata, new TypeReference<>() {
        });
    }

    private Object unwrapJsonValue(Object value) {
        if (value == null || value instanceof JsonNull) {
            return null;
        }
        if (value instanceof JsonPrimitive primitive) {
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            }
            if (primitive.isNumber()) {
                return primitive.getAsNumber();
            }
            if (primitive.isString()) {
                return primitive.getAsString();
            }
        }
        if (value instanceof JsonArray array) {
            List<Object> items = new ArrayList<>(array.size());
            for (JsonElement element : array) {
                items.add(unwrapJsonValue(element));
            }
            return items;
        }
        if (value instanceof JsonObject object) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                map.put(entry.getKey(), unwrapJsonValue(entry.getValue()));
            }
            return map;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                normalized.put(entry.getKey().toString(), unwrapJsonValue(entry.getValue()));
            }
            return normalized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> normalized = new ArrayList<>();
            for (Object item : iterable) {
                normalized.add(unwrapJsonValue(item));
            }
            return normalized;
        }
        return value;
    }

    private Map<String, Object> normalizeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>(metadata);
    }

    private String truncate(String value, int maxLength) {
        String normalized = defaultText(value);
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
    }

    private String defaultText(Object value) {
        return Objects.toString(value, "");
    }
}
