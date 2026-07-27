package com.luky.nexusmind.service;

import com.luky.nexusmind.entity.SearchResult;
import com.luky.nexusmind.model.FileUpload;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class KnowledgeGraphRetrievalService {
    private final KnowledgeGraphStoreService graphStoreService;
    private final DocumentService documentService;

    public KnowledgeGraphRetrievalService(KnowledgeGraphStoreService graphStoreService,
                                          DocumentService documentService) {
        this.graphStoreService = graphStoreService;
        this.documentService = documentService;
    }

    public String buildContext(String userId, String query, List<SearchResult> searchResults) {
        if (!graphStoreService.isEnabled()) return "";
        List<FileUpload> accessible = documentService.getAccessibleFiles(userId, "");
        String seed = buildSeed(query, searchResults);
        List<KnowledgeGraphStoreService.GraphPath> paths = graphStoreService.search(
                seed, accessible.stream().map(FileUpload::getId).toList(), 8);
        if (paths.isEmpty()) return "";
        StringBuilder context = new StringBuilder("以下是知识图谱找到的跨文档关系路径：\n");
        int pathIndex = 1;
        int sourceIndex = (searchResults == null ? 0 : searchResults.size()) + 1;
        for (KnowledgeGraphStoreService.GraphPath path : paths) {
            context.append("关系路径 ").append(pathIndex++).append(": ");
            List<java.util.Map<String, Object>> nodes = path.nodes();
            List<java.util.Map<String, Object>> relations = path.relations();
            for (int i = 0; i < relations.size() && i + 1 < nodes.size(); i++) {
                if (i == 0) context.append(nodes.get(i).get("name"));
                java.util.Map<String, Object> relation = relations.get(i);
                boolean forward = java.util.Objects.equals(relation.get("source"), nodes.get(i).get("key"));
                if (forward) {
                    context.append(" --").append(relation.get("predicate")).append("--> ");
                } else {
                    context.append(" <--").append(relation.get("predicate")).append("-- ");
                }
                context.append(nodes.get(i + 1).get("name"));
            }
            context.append("\n");
            for (java.util.Map<String, Object> relation : relations) {
                context.append("[").append(sourceIndex++).append("] (")
                        .append(relation.get("fileName")).append(") ")
                        .append(relation.get("evidence")).append("\n");
            }
        }
        return context.toString();
    }

    private String buildSeed(String query, List<SearchResult> results) {
        String snippets = results == null ? "" : results.stream().limit(5)
                .map(SearchResult::getTextContent)
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.length() > 300 ? value.substring(0, 300) : value)
                .collect(Collectors.joining("\n"));
        return query + "\n" + snippets;
    }
}
