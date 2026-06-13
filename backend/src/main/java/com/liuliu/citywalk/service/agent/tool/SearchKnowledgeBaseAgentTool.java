package com.liuliu.citywalk.service.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.service.rag.KnowledgeHit;
import com.liuliu.citywalk.service.rag.KnowledgeSearchService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(prefix = "liuliu.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SearchKnowledgeBaseAgentTool extends AbstractJsonAgentTool {

    private final KnowledgeSearchService knowledgeSearchService;

    public SearchKnowledgeBaseAgentTool(ObjectMapper objectMapper, KnowledgeSearchService knowledgeSearchService) {
        super(objectMapper);
        this.knowledgeSearchService = knowledgeSearchService;
    }

    @Override
    public String name() {
        return "search_knowledge_base";
    }

    @Override
    public String description() {
        return "检索已经向量化入库的 City Walk 知识片段，比如公开攻略、路线摘要和历史漫步内容。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of(
                                "type", "string",
                                "description", "要检索的语义查询，比如海边日落、校园散步、老街拍照。"
                        ),
                        "topK", Map.of(
                                "type", "integer",
                                "description", "返回结果数量，默认 5。"
                        ),
                        "sourceType", Map.of(
                                "type", "string",
                                "description", "可选，限制知识来源类型，比如 community_walk。"
                        )
                ),
                "required", List.of("query"),
                "additionalProperties", false
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String query = stringArg(arguments, "query");
        int topK = Math.min(Math.max(1, intArg(arguments, "topK", 5)), 10);
        String sourceType = stringArg(arguments, "sourceType");

        Map<String, Object> filters = new LinkedHashMap<>();
        if (!sourceType.isBlank()) {
            filters.put("source_type", sourceType);
        }

        List<KnowledgeHit> hits = knowledgeSearchService.search(query, topK, filters);
        return json(Map.of(
                "success", true,
                "query", query,
                "topK", topK,
                "sourceType", sourceType,
                "results", hits
        ));
    }
}
