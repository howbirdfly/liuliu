package com.liuliu.citywalk.service.rag;

import com.liuliu.citywalk.config.MilvusProperties;
import io.milvus.v2.service.utility.response.CheckHealthResp;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MilvusVectorStore implements VectorStore {

    private final MilvusClientProvider milvusClientProvider;

    public MilvusVectorStore(MilvusClientProvider milvusClientProvider) {
        this.milvusClientProvider = milvusClientProvider;
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
        // 这里先保留骨架，不在第一步里把 collection/schema 写死，后续接 embedding 维度和 metadata 字段时再统一创建。
        if (properties.getCollection() == null || properties.getCollection().isBlank()) {
            throw new IllegalStateException("milvus_collection_required");
        }
    }

    @Override
    public void upsertDocuments(List<KnowledgeDocument> documents) {
        ensureCollection();
        if (documents == null || documents.isEmpty()) {
            return;
        }
        // 第一阶段先把接口骨架和 Milvus 健康检查搭起来，真正 upsert 会在接切片/embedding 后补齐。
        throw new UnsupportedOperationException("milvus_upsert_not_implemented_yet");
    }

    @Override
    public List<KnowledgeHit> search(VectorSearchQuery query) {
        ensureCollection();
        if (query == null || query.embedding() == null || query.embedding().isEmpty()) {
            return List.of();
        }
        // 第一阶段先不把检索参数写死，等 embedding 模型和 filter 字段定下来后再补正式 search。
        throw new UnsupportedOperationException("milvus_search_not_implemented_yet");
    }

    @Override
    public void deleteBySource(String sourceType, String sourceId) {
        ensureCollection();
        if ((sourceType == null || sourceType.isBlank()) && (sourceId == null || sourceId.isBlank())) {
            return;
        }
        throw new UnsupportedOperationException("milvus_delete_not_implemented_yet");
    }
}
