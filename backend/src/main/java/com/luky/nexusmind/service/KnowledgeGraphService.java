package com.luky.nexusmind.service;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.model.*;
import com.luky.nexusmind.repository.FileUploadRepository;
import com.luky.nexusmind.repository.GraphCandidateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeGraphService {
    private final FileUploadRepository fileUploadRepository;
    private final GraphCandidateRepository candidateRepository;
    private final KnowledgeGraphStoreService storeService;
    private final KnowledgeGraphExtractionService extractionService;

    public KnowledgeGraphService(FileUploadRepository fileUploadRepository,
                                 GraphCandidateRepository candidateRepository,
                                 KnowledgeGraphStoreService storeService,
                                 KnowledgeGraphExtractionService extractionService) {
        this.fileUploadRepository = fileUploadRepository;
        this.candidateRepository = candidateRepository;
        this.storeService = storeService;
        this.extractionService = extractionService;
    }

    @Transactional(readOnly = true)
    public DocumentGraphResponse get(String fileMd5, String userId, String role) {
        FileUpload file = requireManageableFile(fileMd5, userId, role);
        return response(file);
    }

    @Transactional
    public CandidateResponse updateCandidate(String fileMd5, Long candidateId, String userId, String role,
                                             CandidateUpdate request) {
        FileUpload file = requireManageableFile(fileMd5, userId, role);
        GraphCandidate candidate = candidateRepository.findById(candidateId)
                .filter(value -> value.getFileUploadId().equals(file.getId()))
                .orElseThrow(() -> new CustomException("候选关系不存在", HttpStatus.NOT_FOUND));
        if (candidate.getStatus() != GraphCandidateStatus.PENDING) {
            throw new CustomException("已发布的关系不能在候选区修改", HttpStatus.CONFLICT);
        }
        if (request.selected() != null) candidate.setSelected(request.selected());
        if (hasText(request.subjectName())) candidate.setSubjectName(request.subjectName().trim());
        if (hasText(request.subjectType())) candidate.setSubjectType(request.subjectType().trim().toUpperCase());
        if (hasText(request.predicate())) candidate.setPredicate(request.predicate().trim());
        if (hasText(request.objectName())) candidate.setObjectName(request.objectName().trim());
        if (hasText(request.objectType())) candidate.setObjectType(request.objectType().trim().toUpperCase());
        return candidate(candidateRepository.save(candidate));
    }

    @Transactional
    public DocumentGraphResponse publish(String fileMd5, String userId, String role) {
        FileUpload file = requireManageableFile(fileMd5, userId, role);
        if (file.getGraphStatus() != KnowledgeGraphStatus.PENDING_REVIEW) {
            throw new CustomException("当前文档没有待确认的图谱结果", HttpStatus.CONFLICT);
        }
        List<GraphCandidate> selected = candidateRepository
                .findByFileUploadIdAndStatusAndSelectedTrueOrderByIdAsc(file.getId(), GraphCandidateStatus.PENDING);
        if (selected.isEmpty()) throw new CustomException("请至少选择一条关系", HttpStatus.BAD_REQUEST);
        storeService.ensureConstraints();
        storeService.publish(file, selected);
        List<GraphCandidate> all = candidateRepository.findByFileUploadIdOrderByEvidenceChunkIdAscIdAsc(file.getId());
        all.forEach(value -> value.setStatus(value.isSelected()
                ? GraphCandidateStatus.PUBLISHED : GraphCandidateStatus.REJECTED));
        candidateRepository.saveAll(all);
        file.setGraphStatus(KnowledgeGraphStatus.PUBLISHED);
        file.setGraphError(null);
        fileUploadRepository.save(file);
        return response(file);
    }

    @Transactional
    public DocumentGraphResponse setEnabled(String fileMd5, String userId, String role, boolean enabled) {
        FileUpload file = requireManageableFile(fileMd5, userId, role);
        file.setGraphEnabled(enabled);
        file.setGraphError(null);
        if (!enabled) {
            storeService.removeDocument(file.getId());
            file.setGraphStatus(KnowledgeGraphStatus.DISABLED);
            fileUploadRepository.save(file);
            return response(file);
        }
        file.setGraphStatus(KnowledgeGraphStatus.QUEUED);
        fileUploadRepository.save(file);
        extractAfterCommit(file);
        return response(file);
    }

    @Transactional
    public DocumentGraphResponse rebuild(String fileMd5, String userId, String role) {
        FileUpload file = requireManageableFile(fileMd5, userId, role);
        if (!file.isGraphEnabled()) throw new CustomException("请先开启知识图谱", HttpStatus.CONFLICT);
        storeService.removeDocument(file.getId());
        file.setGraphStatus(KnowledgeGraphStatus.QUEUED);
        file.setGraphError(null);
        fileUploadRepository.save(file);
        extractAfterCommit(file);
        return response(file);
    }

    @Transactional
    public void removeDocument(FileUpload file) {
        if (file == null) return;
        storeService.removeDocument(file.getId());
        candidateRepository.deleteByFileUploadId(file.getId());
    }

    private FileUpload requireManageableFile(String fileMd5, String userId, String role) {
        if ("ADMIN".equals(role)) {
            return fileUploadRepository.findByFileMd5(fileMd5)
                    .orElseThrow(() -> new CustomException("文档不存在", HttpStatus.NOT_FOUND));
        }
        return fileUploadRepository.findByFileMd5AndUserId(fileMd5, userId)
                .orElseThrow(() -> new CustomException("只有上传者或管理员可以管理图谱", HttpStatus.FORBIDDEN));
    }

    private DocumentGraphResponse response(FileUpload file) {
        List<CandidateResponse> candidates = candidateRepository
                .findByFileUploadIdOrderByEvidenceChunkIdAscIdAsc(file.getId()).stream()
                .map(this::candidate).toList();
        KnowledgeGraphStatus status = file.getGraphStatus() == null
                ? KnowledgeGraphStatus.DISABLED : file.getGraphStatus();
        GraphVisualization visualization = visualization(candidates);
        return new DocumentGraphResponse(file.getFileMd5(), file.isGraphEnabled(), status,
                file.getGraphError(), candidates, visualization.nodes(), visualization.edges(), storeService.isEnabled());
    }

    private GraphVisualization visualization(List<CandidateResponse> candidates) {
        Map<String, MutableGraphNode> nodes = new LinkedHashMap<>();
        List<GraphEdgeResponse> edges = candidates.stream()
                .filter(value -> value.status() == GraphCandidateStatus.PUBLISHED
                        || value.status() == GraphCandidateStatus.PENDING && value.selected())
                .map(value -> {
                    String source = nodeId(value.subjectType(), value.subjectName());
                    String target = nodeId(value.objectType(), value.objectName());
                    nodes.computeIfAbsent(source,
                            ignored -> new MutableGraphNode(source, value.subjectName(), value.subjectType()))
                            .incrementDegree();
                    nodes.computeIfAbsent(target,
                            ignored -> new MutableGraphNode(target, value.objectName(), value.objectType()))
                            .incrementDegree();
                    return new GraphEdgeResponse("candidate-" + value.id(), source, target, value.predicate(),
                            value.confidence() == null ? 0.0 : value.confidence(), value.evidenceChunkId(),
                            value.evidenceText(), value.status());
                })
                .toList();
        return new GraphVisualization(nodes.values().stream().map(MutableGraphNode::response).toList(), edges);
    }

    private String nodeId(String type, String name) {
        return KnowledgeGraphStoreService.normalizeType(type) + "|" + KnowledgeGraphStoreService.normalizeName(name);
    }

    private CandidateResponse candidate(GraphCandidate value) {
        return new CandidateResponse(value.getId(), value.getSubjectName(), value.getSubjectType(), value.getPredicate(),
                value.getObjectName(), value.getObjectType(), value.getEvidenceChunkId(), value.getEvidenceText(),
                value.getConfidence(), value.isSelected(), value.getStatus());
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }

    private void extractAfterCommit(FileUpload file) {
        Runnable task = () -> extractionService.extractAsync(file.getFileMd5(), file.getUserId());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    public record DocumentGraphResponse(String fileMd5, boolean enabled, KnowledgeGraphStatus status,
                                        String error, List<CandidateResponse> candidates,
                                        List<GraphNodeResponse> nodes, List<GraphEdgeResponse> edges,
                                        boolean neo4jEnabled) {}
    public record CandidateResponse(Long id, String subjectName, String subjectType, String predicate,
                                    String objectName, String objectType, Integer evidenceChunkId,
                                    String evidenceText, Double confidence, boolean selected,
                                    GraphCandidateStatus status) {}
    public record GraphNodeResponse(String id, String name, String type, int degree) {}
    public record GraphEdgeResponse(String id, String source, String target, String predicate,
                                    Double confidence, Integer evidenceChunkId, String evidenceText,
                                    GraphCandidateStatus status) {}
    public record CandidateUpdate(Boolean selected, String subjectName, String subjectType,
                                  String predicate, String objectName, String objectType) {}
    public record EnabledRequest(boolean enabled) {}

    private record GraphVisualization(List<GraphNodeResponse> nodes, List<GraphEdgeResponse> edges) {}

    private static final class MutableGraphNode {
        private final String id;
        private final String name;
        private final String type;
        private int degree;

        private MutableGraphNode(String id, String name, String type) {
            this.id = id;
            this.name = name;
            this.type = type;
        }

        private void incrementDegree() {
            degree++;
        }

        private GraphNodeResponse response() {
            return new GraphNodeResponse(id, name, type, degree);
        }
    }
}
