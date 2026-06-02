package com.liuliu.citywalk.service.rag;

import java.util.List;

public interface KnowledgeRetrievalService {

    List<KnowledgeHit> retrieve(VectorSearchQuery query);
}
