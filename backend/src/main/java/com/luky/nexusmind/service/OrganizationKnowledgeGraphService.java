package com.luky.nexusmind.service;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.model.FileUpload;
import com.luky.nexusmind.model.KnowledgeGraphStatus;
import com.luky.nexusmind.model.OrganizationTag;
import com.luky.nexusmind.repository.OrganizationTagRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OrganizationKnowledgeGraphService {
    private static final int DEFAULT_RELATION_LIMIT = 300;
    private static final int MAX_RELATION_LIMIT = 1000;

    private final DocumentService documentService;
    private final OrganizationTagRepository organizationTagRepository;
    private final KnowledgeGraphStoreService graphStoreService;

    public OrganizationKnowledgeGraphService(DocumentService documentService,
                                             OrganizationTagRepository organizationTagRepository,
                                             KnowledgeGraphStoreService graphStoreService) {
        this.documentService = documentService;
        this.organizationTagRepository = organizationTagRepository;
        this.graphStoreService = graphStoreService;
    }

    @Transactional(readOnly = true)
    public List<OrganizationOption> listOrganizations(String userId, String role) {
        return scopeSelections(userId, role).stream().map(OrganizationScopeSelection::option).toList();
    }

    @Transactional(readOnly = true)
    public OrganizationGraphResponse getOrganizationGraph(String requestedScope,
                                                           String userId,
                                                           String role,
                                                           String query,
                                                           String entityType,
                                                           Collection<Long> requestedFileIds,
                                                           Integer requestedLimit) {
        List<OrganizationScopeSelection> selections = scopeSelections(userId, role);
        OrganizationScopeSelection selection = selections.stream()
                .filter(value -> value.scopeId().equals(requestedScope))
                .findFirst()
                // Keep the previous org-tag URL compatible: members get the internal graph,
                // other users get the public graph when one exists.
                .orElseGet(() -> selections.stream()
                        .filter(value -> value.tagId().equals(requestedScope))
                        .min(Comparator.comparingInt(value -> value.scopeType() == ScopeType.INTERNAL ? 0 : 1))
                        .orElse(null));
        if (selection == null) {
            throw new CustomException("无权访问该组织知识图谱", HttpStatus.FORBIDDEN);
        }

        List<FileUpload> publishedFiles = selection.files().stream().filter(this::isPublished).toList();
        Set<Long> requestedIds = requestedFileIds == null
                ? Set.of()
                : requestedFileIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        List<FileUpload> selectedFiles = requestedIds.isEmpty()
                ? publishedFiles
                : publishedFiles.stream().filter(file -> requestedIds.contains(file.getId())).toList();

        int limit = requestedLimit == null
                ? DEFAULT_RELATION_LIMIT
                : Math.min(Math.max(requestedLimit, 1), MAX_RELATION_LIMIT);
        List<KnowledgeGraphStoreService.OrganizationRelation> loaded = graphStoreService.loadOrganizationRelations(
                queryScopeIds(selection),
                selectedFiles.stream().map(FileUpload::getId).toList(),
                query,
                entityType,
                limit + 1
        );
        Map<FactKey, MutableFact> loadedFacts = new LinkedHashMap<>();
        loaded.forEach(relation -> loadedFacts
                .computeIfAbsent(FactKey.of(relation), ignored -> new MutableFact(relation))
                .addEvidence(relation));
        boolean truncated = loadedFacts.size() > limit;
        List<MutableFact> facts = loadedFacts.values().stream().limit(limit).toList();
        Map<StatementKey, Set<String>> statementTargets = new HashMap<>();
        facts.forEach(fact -> statementTargets
                .computeIfAbsent(fact.statementKey(), ignored -> new HashSet<>()).add(fact.targetKey));

        Map<String, MutableNode> nodes = new LinkedHashMap<>();
        List<GraphEdge> edges = facts.stream().map(fact -> {
            nodes.computeIfAbsent(fact.sourceKey, ignored -> new MutableNode(
                    fact.sourceKey, fact.sourceName, fact.sourceType)).incrementDegree();
            nodes.computeIfAbsent(fact.targetKey, ignored -> new MutableNode(
                    fact.targetKey, fact.targetName, fact.targetType)).incrementDegree();
            return fact.response(statementTargets.getOrDefault(fact.statementKey(), Set.of()).size() > 1);
        }).toList();

        Topology topology = analyzeTopology(nodes, edges);
        List<GraphNode> graphNodes = nodes.values().stream().map(node -> node.response(
                topology.componentIds().get(node.id), topology.communityIds().get(node.id),
                topology.importance().getOrDefault(node.id, 0.0))).toList();
        List<String> entityTypes = graphNodes.stream().map(GraphNode::type).distinct().sorted().toList();
        Set<Long> contributingDocuments = edges.stream().flatMap(edge -> edge.evidences().stream())
                .map(GraphEvidence::fileUploadId).collect(Collectors.toSet());
        List<DocumentOption> documents = publishedFiles.stream()
                .sorted(Comparator.comparing(FileUpload::getFileName, String.CASE_INSENSITIVE_ORDER))
                .map(file -> new DocumentOption(file.getId(), file.getFileMd5(), file.getFileName()))
                .toList();

        return new OrganizationGraphResponse(
                selection.scopeId(),
                selection.tagId(),
                selection.name(),
                selection.scopeType(),
                graphNodes,
                edges,
                topology.communities(),
                entityTypes,
                documents,
                new GraphStats(graphNodes.size(), edges.size(), contributingDocuments.size(),
                        (int) edges.stream().filter(GraphEdge::disputed).count(),
                        (int) edges.stream().filter(GraphEdge::crossDocument).count()),
                truncated,
                graphStoreService.isEnabled()
        );
    }

    private List<OrganizationScopeSelection> scopeSelections(String userId, String role) {
        List<FileUpload> accessible = documentService.getAccessibleFiles(userId, "", role).stream()
                .filter(file -> !file.isLegacyShared())
                .filter(file -> file.getOrgTag() != null && !file.getOrgTag().isBlank())
                .toList();
        Set<String> memberships = isAdministrator(role)
                ? Set.of()
                : new HashSet<>(documentService.getEffectiveOrganizationTags(userId));
        Map<String, String> names = organizationTagRepository.findAll().stream()
                .collect(Collectors.toMap(OrganizationTag::getTagId, OrganizationTag::getName, (left, right) -> left));
        Map<String, MutableScope> scopes = new LinkedHashMap<>();

        for (FileUpload file : accessible) {
            String orgTag = file.getOrgTag();
            String orgName = names.getOrDefault(orgTag, orgTag);
            if (DocumentPermissionPolicy.isPrivateOrgTag(orgTag)) {
                addScope(scopes, KnowledgeGraphStoreService.privateScope(orgTag), orgTag,
                        orgName, ScopeType.PRIVATE, file);
                continue;
            }
            if (file.isPublic()) {
                addScope(scopes, KnowledgeGraphStoreService.publicOrganizationScope(orgTag), orgTag,
                        orgName, ScopeType.PUBLIC, file);
            }
            if (isAdministrator(role) || memberships.contains(orgTag)) {
                addScope(scopes, KnowledgeGraphStoreService.internalOrganizationScope(orgTag), orgTag,
                        orgName, ScopeType.INTERNAL, file);
            }
        }

        return scopes.values().stream()
                .map(MutableScope::selection)
                .sorted(Comparator.comparing(OrganizationScopeSelection::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(OrganizationScopeSelection::scopeType))
                .toList();
    }

    private boolean isAdministrator(String role) {
        return "ADMIN".equals(role) || "SUPER_ADMIN".equals(role);
    }

    private void addScope(Map<String, MutableScope> scopes, String scopeId, String tagId,
                          String name, ScopeType scopeType, FileUpload file) {
        scopes.computeIfAbsent(scopeId, ignored -> new MutableScope(scopeId, tagId, name, scopeType)).files.add(file);
    }

    private List<String> queryScopeIds(OrganizationScopeSelection selection) {
        return switch (selection.scopeType()) {
            case PUBLIC -> List.of(selection.scopeId(), "PUBLIC");
            case INTERNAL -> List.of(selection.scopeId(), "ORG:" + selection.tagId(), "PUBLIC");
            case PRIVATE -> {
                List<String> scopeIds = new ArrayList<>();
                scopeIds.add(selection.scopeId());
                selection.files().stream().map(FileUpload::getUserId).filter(Objects::nonNull).distinct()
                        .map(ownerId -> "USER:" + ownerId).forEach(scopeIds::add);
                yield scopeIds;
            }
        };
    }

    private boolean isPublished(FileUpload file) {
        return file.isGraphEnabled() && file.getGraphStatus() == KnowledgeGraphStatus.PUBLISHED;
    }

    public enum ScopeType { PUBLIC, INTERNAL, PRIVATE }

    public record OrganizationOption(String scopeId, String tagId, String name, ScopeType scopeType,
                                     int documentCount, long publishedDocumentCount) {}

    public record OrganizationGraphResponse(String scopeId, String orgTag, String orgName, ScopeType scopeType,
                                            List<GraphNode> nodes, List<GraphEdge> edges,
                                            List<Community> communities,
                                            List<String> entityTypes, List<DocumentOption> documents,
                                            GraphStats stats, boolean truncated, boolean neo4jEnabled) {}

    public record GraphNode(String id, String name, String type, int degree,
                            String componentId, String communityId, double importance) {}

    public record Community(String id, String componentId, String label,
                            int nodeCount, int relationCount, int documentCount) {}

    public record GraphEdge(String id, String source, String target, String predicate,
                            Double confidence, Integer evidenceChunkId, String evidenceText,
                            Long fileUploadId, String fileMd5, String fileName,
                            int supportCount, int documentCount, boolean disputed,
                            String relationKind, boolean crossDocument,
                            List<GraphEvidence> evidences) {}

    public record GraphEvidence(Long claimId, Long fileUploadId, String fileMd5, String fileName,
                                Integer chunkId, String evidenceText, Double confidence) {}

    public record DocumentOption(Long id, String fileMd5, String fileName) {}

    public record GraphStats(int entityCount, int relationCount, int documentCount,
                             int disputedRelationCount, int crossDocumentRelationCount) {}

    private record OrganizationScopeSelection(String scopeId, String tagId, String name, ScopeType scopeType,
                                              List<FileUpload> files) {
        private OrganizationOption option() {
            return new OrganizationOption(scopeId, tagId, name, scopeType, files.size(),
                    files.stream().filter(file -> file.isGraphEnabled()
                            && file.getGraphStatus() == KnowledgeGraphStatus.PUBLISHED).count());
        }
    }

    private static final class MutableScope {
        private final String scopeId;
        private final String tagId;
        private final String name;
        private final ScopeType scopeType;
        private final List<FileUpload> files = new ArrayList<>();

        private MutableScope(String scopeId, String tagId, String name, ScopeType scopeType) {
            this.scopeId = scopeId;
            this.tagId = tagId;
            this.name = name;
            this.scopeType = scopeType;
        }

        private OrganizationScopeSelection selection() {
            return new OrganizationScopeSelection(scopeId, tagId, name, scopeType, List.copyOf(files));
        }
    }

    private static final class MutableNode {
        private final String id;
        private final String name;
        private final String type;
        private int degree;

        private MutableNode(String id, String name, String type) {
            this.id = id;
            this.name = name;
            this.type = type;
        }

        private void incrementDegree() {
            degree++;
        }

        private GraphNode response(String componentId, String communityId, double importance) {
            return new GraphNode(id, name, type, degree, componentId, communityId, importance);
        }
    }

    private record Topology(Map<String, String> componentIds,
                            Map<String, String> communityIds,
                            Map<String, Double> importance,
                            List<Community> communities) {}

    static Topology analyzeTopology(Map<String, MutableNode> nodes, List<GraphEdge> edges) {
        Map<String, Map<String, Double>> adjacency = new LinkedHashMap<>();
        nodes.keySet().stream().sorted().forEach(id -> adjacency.put(id, new LinkedHashMap<>()));
        for (GraphEdge edge : edges) {
            double weight = 1.0 + Math.log1p(Math.max(0, edge.supportCount() - 1))
                    + (edge.crossDocument() ? 0.35 : 0.0);
            adjacency.get(edge.source()).merge(edge.target(), weight, Double::sum);
            adjacency.get(edge.target()).merge(edge.source(), weight, Double::sum);
        }

        Map<String, Double> rank = new LinkedHashMap<>();
        int nodeCount = Math.max(1, nodes.size());
        for (String id : adjacency.keySet()) rank.put(id, 1.0 / nodeCount);
        for (int iteration = 0; iteration < 24; iteration++) {
            Map<String, Double> next = new LinkedHashMap<>();
            adjacency.keySet().forEach(id -> next.put(id, 0.15 / nodeCount));
            for (String id : adjacency.keySet()) {
                Map<String, Double> neighbors = adjacency.get(id);
                double total = neighbors.values().stream().mapToDouble(Double::doubleValue).sum();
                if (total == 0) {
                    double share = 0.85 * rank.get(id) / nodeCount;
                    next.replaceAll((ignored, value) -> value + share);
                } else {
                    for (var neighbor : neighbors.entrySet()) {
                        next.merge(neighbor.getKey(), 0.85 * rank.get(id) * neighbor.getValue() / total, Double::sum);
                    }
                }
            }
            rank = next;
        }
        double maxRank = rank.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        Map<String, Double> importance = new LinkedHashMap<>();
        for (String id : adjacency.keySet()) importance.put(id, rank.get(id) / Math.max(maxRank, 1e-9));

        Map<String, String> componentIds = new LinkedHashMap<>();
        List<List<String>> components = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String seed : adjacency.keySet()) {
            if (!seen.add(seed)) continue;
            List<String> component = new ArrayList<>();
            ArrayDeque<String> queue = new ArrayDeque<>();
            queue.add(seed);
            while (!queue.isEmpty()) {
                String id = queue.removeFirst();
                component.add(id);
                adjacency.get(id).keySet().stream().sorted().forEach(neighbor -> {
                    if (seen.add(neighbor)) queue.addLast(neighbor);
                });
            }
            component.sort(String::compareTo);
            components.add(component);
        }
        components.sort(Comparator.<List<String>>comparingInt(List::size).reversed()
                .thenComparing(component -> component.get(0)));
        for (List<String> component : components) {
            String componentId = stableId("component", component);
            component.forEach(id -> componentIds.put(id, componentId));
        }

        Map<String, String> labels = new LinkedHashMap<>();
        adjacency.keySet().forEach(id -> labels.put(id, id));
        List<String> visitOrder = adjacency.keySet().stream()
                .sorted(Comparator.<String>comparingDouble(importance::get).reversed().thenComparing(id -> id))
                .toList();
        for (int iteration = 0; iteration < 16; iteration++) {
            boolean changed = false;
            for (String id : visitOrder) {
                if (adjacency.get(id).isEmpty()) continue;
                Map<String, Double> scores = new HashMap<>();
                for (var neighbor : adjacency.get(id).entrySet()) {
                    scores.merge(labels.get(neighbor.getKey()), neighbor.getValue(), Double::sum);
                }
                scores.merge(labels.get(id), adjacency.get(id).values().stream()
                        .mapToDouble(Double::doubleValue).sum() * 0.28, Double::sum);
                String best = scores.entrySet().stream()
                        .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                                .thenComparing(Map.Entry::getKey))
                        .map(Map.Entry::getKey).findFirst().orElse(labels.get(id));
                if (!best.equals(labels.get(id))) {
                    labels.put(id, best);
                    changed = true;
                }
            }
            if (!changed) break;
        }
        // Singletons inside a connected component join their strongest neighboring community.
        Map<String, Long> sizes = labels.values().stream()
                .collect(Collectors.groupingBy(value -> value, Collectors.counting()));
        for (String id : visitOrder) {
            if (sizes.getOrDefault(labels.get(id), 0L) != 1 || adjacency.get(id).isEmpty()) continue;
            String best = adjacency.get(id).entrySet().stream()
                    .max(Map.Entry.<String, Double>comparingByValue()
                            .thenComparing(entry -> labels.get(entry.getKey())))
                    .map(entry -> labels.get(entry.getKey())).orElse(labels.get(id));
            labels.put(id, best);
        }

        Map<String, List<String>> membersByLabel = new LinkedHashMap<>();
        for (String id : adjacency.keySet()) membersByLabel
                .computeIfAbsent(labels.get(id), ignored -> new ArrayList<>()).add(id);
        List<List<String>> communitiesByMembers = membersByLabel.values().stream()
                .peek(members -> members.sort(String::compareTo))
                .sorted(Comparator.<List<String>>comparingInt(List::size).reversed()
                        .thenComparing(members -> members.get(0))).toList();
        Map<String, String> communityIds = new LinkedHashMap<>();
        List<Community> communities = new ArrayList<>();
        for (List<String> members : communitiesByMembers) {
            String communityId = stableId("community", members);
            String componentId = componentIds.get(members.get(0));
            members.forEach(id -> communityIds.put(id, communityId));
            Set<String> memberSet = new HashSet<>(members);
            List<GraphEdge> communityEdges = edges.stream()
                    .filter(edge -> memberSet.contains(edge.source()) && memberSet.contains(edge.target())).toList();
            int documentCount = (int) communityEdges.stream().flatMap(edge -> edge.evidences().stream())
                    .map(GraphEvidence::fileUploadId).distinct().count();
            String label = members.stream()
                    .sorted(Comparator.<String>comparingDouble(importance::get).reversed().thenComparing(id -> id))
                    .limit(2).map(id -> nodes.get(id).name).collect(Collectors.joining(" / "));
            communities.add(new Community(communityId, componentId, label, members.size(),
                    communityEdges.size(), documentCount));
        }
        return new Topology(Map.copyOf(componentIds), Map.copyOf(communityIds),
                Map.copyOf(importance), List.copyOf(communities));
    }

    private static String stableId(String prefix, List<String> members) {
        UUID id = UUID.nameUUIDFromBytes(String.join("\u001f", members).getBytes(StandardCharsets.UTF_8));
        return prefix + "-" + id.toString().substring(0, 8);
    }

    private record FactKey(String sourceKey, String predicate, String targetKey) {
        private static FactKey of(KnowledgeGraphStoreService.OrganizationRelation relation) {
            return new FactKey(relation.sourceKey(), normalizePredicate(relation.predicate()), relation.targetKey());
        }
    }

    private record StatementKey(String sourceKey, String predicate) {}

    private static String normalizePredicate(String value) {
        return Objects.toString(value, "").trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static final class MutableFact {
        private final String sourceKey;
        private final String sourceName;
        private final String sourceType;
        private final String targetKey;
        private final String targetName;
        private final String targetType;
        private final String predicate;
        private final List<GraphEvidence> evidences = new ArrayList<>();

        private MutableFact(KnowledgeGraphStoreService.OrganizationRelation relation) {
            sourceKey = relation.sourceKey();
            sourceName = relation.sourceName();
            sourceType = relation.sourceType();
            targetKey = relation.targetKey();
            targetName = relation.targetName();
            targetType = relation.targetType();
            predicate = relation.predicate();
        }

        private void addEvidence(KnowledgeGraphStoreService.OrganizationRelation relation) {
            evidences.add(new GraphEvidence(relation.candidateId(), relation.fileUploadId(), relation.fileMd5(),
                    relation.fileName(), relation.chunkId(), relation.evidence(), relation.confidence()));
        }

        private StatementKey statementKey() {
            return new StatementKey(sourceKey, normalizePredicate(predicate));
        }

        private GraphEdge response(boolean disputed) {
            GraphEvidence primary = evidences.stream().max(Comparator.comparingDouble(
                    value -> value.confidence() == null ? 0.0 : value.confidence())).orElseThrow();
            int documents = (int) evidences.stream().map(GraphEvidence::fileUploadId).distinct().count();
            return new GraphEdge("claim-" + primary.claimId(), sourceKey, targetKey, predicate,
                    primary.confidence(), primary.chunkId(), primary.evidenceText(), primary.fileUploadId(),
                    primary.fileMd5(), primary.fileName(), evidences.size(), documents, disputed,
                    "ASSERTED", documents > 1, List.copyOf(evidences));
        }
    }
}
