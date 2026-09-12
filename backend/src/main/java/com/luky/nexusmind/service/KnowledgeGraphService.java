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
    @org.springframework.beans.factory.annotation.Autowired private GraphExtractionEngine engine;

    private final FileUploadRepository fileUploadRepository;
    private final GraphCandidateRepository candidateRepository;
    private final KnowledgeGraphStoreService storeService;
    private final KnowledgeGraphExtractionService extractionService;
    private final GraphPromptTemplateService templateService;

    public KnowledgeGraphService(
            FileUploadRepository fileUploadRepository,
            GraphCandidateRepository candidateRepository,
            KnowledgeGraphStoreService storeService,
            KnowledgeGraphExtractionService extractionService,
            GraphPromptTemplateService templateService) {
        this.fileUploadRepository = fileUploadRepository;
        this.candidateRepository = candidateRepository;
        this.storeService = storeService;
        this.extractionService = extractionService;
        this.templateService = templateService;
    }

    @Transactional(readOnly = true)
    public DocumentGraphResponse get(String fileMd5, String userId, String role) {
        FileUpload file = requireManageableFile(fileMd5, userId, role);
        return response(file);
    }

    @Transactional
    public CandidateResponse updateCandidate(
            String fileMd5, Long candidateId, String userId, String role, CandidateUpdate request) {
        FileUpload file = requireManageableFile(fileMd5, userId, role);
        GraphCandidate candidate =
                candidateRepository
                        .findById(candidateId)
                        .filter(value -> value.getFileUploadId().equals(file.getId()))
                        .orElseThrow(() -> new CustomException("候选关系不存在", HttpStatus.NOT_FOUND));
        requireIdle(file);
        if (file.getGraphStatus() != KnowledgeGraphStatus.PENDING_REVIEW)
            throw new CustomException("抽取完成并进入审核后才能编辑", HttpStatus.CONFLICT);
        if (candidate.getStatus() != GraphCandidateStatus.PENDING) {
            throw new CustomException("已发布的关系不能在候选区修改", HttpStatus.CONFLICT);
        }
        if (request.selected() != null) candidate.setSelected(request.selected());
        if (hasText(request.subjectName())) candidate.setSubjectName(request.subjectName().trim());
        if (hasText(request.subjectType()))
            candidate.setSubjectType(request.subjectType().trim().toUpperCase());
        if (hasText(request.predicate())) candidate.setPredicate(request.predicate().trim());
        if (hasText(request.objectName())) candidate.setObjectName(request.objectName().trim());
        if (hasText(request.objectType()))
            candidate.setObjectType(request.objectType().trim().toUpperCase());
        return candidate(candidateRepository.save(candidate));
    }

    @Transactional
    public DocumentGraphResponse publish(String fileMd5, String userId, String role) {
        FileUpload file = requireManageableFile(fileMd5, userId, role);
        if (file.getGraphStatus() == KnowledgeGraphStatus.PUBLISHED) return response(file);
        if (file.getGraphStatus() != KnowledgeGraphStatus.PENDING_REVIEW) {
            throw new CustomException("当前文档没有待确认的图谱结果", HttpStatus.CONFLICT);
        }
        List<GraphCandidate> selected =
                candidateRepository.findByFileUploadIdAndStatusAndSelectedTrueOrderByIdAsc(
                        file.getId(), GraphCandidateStatus.PENDING);
        if (selected.isEmpty()) throw new CustomException("请至少选择一条关系", HttpStatus.BAD_REQUEST);
        try {
            storeService.ensureConstraints();
            storeService.publish(file, selected);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(KnowledgeGraphService.class)
                    .error("发布图谱失败，fileUploadId={}", file.getId(), e);
            throw new CustomException("图谱发布失败，请检查 Neo4j 服务状态后重试；审核结果已保留", HttpStatus.SERVICE_UNAVAILABLE);
        }
        List<GraphCandidate> all =
                candidateRepository.findByFileUploadIdOrderByEvidenceChunkIdAscIdAsc(file.getId());
        all.forEach(
                value ->
                        value.setStatus(
                                value.isSelected()
                                        ? GraphCandidateStatus.PUBLISHED
                                        : GraphCandidateStatus.REJECTED));
        candidateRepository.saveAll(all);
        file.setGraphStatus(KnowledgeGraphStatus.PUBLISHED);
        file.setGraphError(null);
        fileUploadRepository.save(file);
        return response(file);
    }

    @Transactional
    public DocumentGraphResponse setEnabled(
            String fileMd5, String userId, String role, boolean enabled, Long templateId) {
        FileUpload file = requireManageableFile(fileMd5, userId, role);
        if (enabled) requireIdle(file);
        file.setGraphRunToken(java.util.UUID.randomUUID().toString());
        file.setGraphEnabled(enabled);
        if (enabled) file.setGraphPromptTemplateId(templateService.resolve(templateId).id());
        file.setGraphError(null);
        if (!enabled) {
            storeService.removeDocument(file.getId());
            file.setGraphStatus(KnowledgeGraphStatus.DISABLED);
            fileUploadRepository.save(file);
            return response(file);
        }
        if (engine != null) engine.resetRun(file.getId());
        file.setGraphStatus(KnowledgeGraphStatus.QUEUED);
        fileUploadRepository.save(file);
        extractAfterCommit(file);
        return response(file);
    }

    @Transactional
    public DocumentGraphResponse rebuild(
            String fileMd5, String userId, String role, Long templateId) {
        FileUpload file = requireManageableFile(fileMd5, userId, role);
        if (!file.isGraphEnabled()) throw new CustomException("请先开启知识图谱", HttpStatus.CONFLICT);
        requireIdle(file);
        file.setGraphRunToken(java.util.UUID.randomUUID().toString());
        file.setGraphPromptTemplateId(
                templateService
                        .resolve(templateId != null ? templateId : file.getGraphPromptTemplateId())
                        .id());
        storeService.removeDocument(file.getId());
        if (engine != null) engine.resetRun(file.getId());
        file.setGraphStatus(KnowledgeGraphStatus.QUEUED);
        file.setGraphError(null);
        fileUploadRepository.save(file);
        extractAfterCommit(file);
        return response(file);
    }

    @Transactional
    public void removeDocument(FileUpload file) {
        if (file == null) return;
        file.setGraphRunToken(java.util.UUID.randomUUID().toString());
        file.setGraphEnabled(false);
        fileUploadRepository.save(file);
        storeService.removeDocument(file.getId());
        candidateRepository.deleteByFileUploadId(file.getId());
        if (engine != null) engine.removeRun(file.getId());
    }

    private void requireIdle(FileUpload file) {
        if (file.getGraphStatus() == KnowledgeGraphStatus.QUEUED
                || file.getGraphStatus() == KnowledgeGraphStatus.EXTRACTING)
            throw new CustomException("图谱任务正在执行，请等待完成或先停用", HttpStatus.CONFLICT);
    }

    @Transactional
    public DocumentGraphResponse retry(String md5, String user, String role) {
        FileUpload file = requireManageableFile(md5, user, role);
        requireIdle(file);
        if (!file.isGraphEnabled()
                || engine.progress(file.getId()) == null
                || !engine.progress(file.getId()).canRetry())
            throw new CustomException("没有可重试的未完成批次", HttpStatus.CONFLICT);
        if (file.getGraphStatus() == KnowledgeGraphStatus.PUBLISHED)
            throw new CustomException("已发布文档请全部重新抽取", HttpStatus.CONFLICT);
        file.setGraphRunToken(java.util.UUID.randomUUID().toString());
        file.setGraphStatus(KnowledgeGraphStatus.QUEUED);
        file.setGraphError(null);
        fileUploadRepository.save(file);
        afterCommit(() -> extractionService.retryAsync(file.getFileMd5(), file.getUserId()));
        return response(file);
    }

    @Transactional
    public DocumentGraphResponse rebuild(
            String md5, String user, String role, Long template, Integer batchChars) {
        FileUpload file = requireManageableFile(md5, user, role);
        requireIdle(file);
        GraphExtractionEngine.validateBatch(file, batchChars);
        fileUploadRepository.save(file);
        return rebuild(md5, user, role, template);
    }

    @Transactional
    public DocumentGraphResponse setEnabled(
            String md5,
            String user,
            String role,
            boolean enabled,
            Long template,
            Integer batchChars) {
        FileUpload file = requireManageableFile(md5, user, role);
        if (enabled) {
            requireIdle(file);
            GraphExtractionEngine.validateBatch(file, batchChars);
            fileUploadRepository.save(file);
        }
        return setEnabled(md5, user, role, enabled, template);
    }

    private FileUpload requireManageableFile(String fileMd5, String userId, String role) {
        if (fileUploadRepository.countByFileMd5(fileMd5) > 1) {
            throw new CustomException("旧文档索引存在共享风险，请删除后重新上传", HttpStatus.CONFLICT);
        }
        boolean write =
                TransactionSynchronizationManager.isActualTransactionActive()
                        && !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        FileUpload file;
        if ("SUPER_ADMIN".equals(role)) {
            file = (write
                            ? fileUploadRepository.lockByMd5(fileMd5)
                            : fileUploadRepository.findByFileMd5(fileMd5))
                    .orElseThrow(() -> new CustomException("文档不存在", HttpStatus.NOT_FOUND));
        } else {
            file = (write
                            ? fileUploadRepository.lockByMd5AndOwner(fileMd5, userId)
                            : fileUploadRepository.findByFileMd5AndUserId(fileMd5, userId))
                    .orElseThrow(() -> new CustomException("只有上传者或超级管理员可以管理图谱", HttpStatus.FORBIDDEN));
        }
        if (file.isLegacyShared()) {
            throw new CustomException("旧文档索引存在共享风险，请删除后重新上传", HttpStatus.CONFLICT);
        }
        return file;
    }

    private DocumentGraphResponse response(FileUpload file) {
        List<CandidateResponse> candidates =
                candidateRepository
                        .findByFileUploadIdOrderByEvidenceChunkIdAscIdAsc(file.getId())
                        .stream()
                        .map(this::candidate)
                        .toList();
        KnowledgeGraphStatus status =
                file.getGraphStatus() == null
                        ? KnowledgeGraphStatus.DISABLED
                        : file.getGraphStatus();
        GraphVisualization visualization = visualization(candidates);
        GraphPromptTemplateService.ResolvedTemplate template =
                templateService.resolve(file.getGraphPromptTemplateId());
        return new DocumentGraphResponse(
                file.getFileMd5(),
                file.isGraphEnabled(),
                status,
                file.getGraphError(),
                file.getGraphPromptTemplateId(),
                template.name(),
                candidates,
                visualization.nodes(),
                visualization.edges(),
                storeService.isEnabled(),
                GraphExtractionEngine.batchChars(file),
                file.getTextChunkSize() == null ? 512 : file.getTextChunkSize(),
                engine == null ? null : engine.progress(file.getId()));
    }

    private GraphVisualization visualization(List<CandidateResponse> candidates) {
        Map<String, MutableGraphNode> nodes = new LinkedHashMap<>();
        List<GraphEdgeResponse> edges =
                candidates.stream()
                        .filter(
                                value ->
                                        value.status() == GraphCandidateStatus.PUBLISHED
                                                || value.status() == GraphCandidateStatus.PENDING
                                                        && value.selected())
                        .map(
                                value -> {
                                    String source =
                                            nodeId(value.subjectType(), value.subjectName());
                                    String target = nodeId(value.objectType(), value.objectName());
                                    nodes.computeIfAbsent(
                                                    source,
                                                    ignored ->
                                                            new MutableGraphNode(
                                                                    source,
                                                                    value.subjectName(),
                                                                    value.subjectType()))
                                            .incrementDegree();
                                    nodes.computeIfAbsent(
                                                    target,
                                                    ignored ->
                                                            new MutableGraphNode(
                                                                    target,
                                                                    value.objectName(),
                                                                    value.objectType()))
                                            .incrementDegree();
                                    return new GraphEdgeResponse(
                                            "candidate-" + value.id(),
                                            source,
                                            target,
                                            value.predicate(),
                                            value.confidence() == null ? 0.0 : value.confidence(),
                                            value.evidenceChunkId(),
                                            value.evidenceText(),
                                            value.status(),
                                            "ASSERTED");
                                })
                        .toList();
        return new GraphVisualization(
                nodes.values().stream().map(MutableGraphNode::response).toList(), edges);
    }

    private String nodeId(String type, String name) {
        return KnowledgeGraphStoreService.normalizeType(type)
                + "|"
                + KnowledgeGraphStoreService.normalizeName(name);
    }

    private CandidateResponse candidate(GraphCandidate value) {
        return new CandidateResponse(
                value.getId(),
                value.getSubjectName(),
                value.getSubjectMentionName(),
                value.getSubjectType(),
                value.getPredicate(),
                value.getObjectName(),
                value.getObjectMentionName(),
                value.getObjectType(),
                value.getEvidenceChunkId(),
                value.getEvidenceText(),
                value.getConfidence(),
                value.getValueScore() == null ? 0.0 : value.getValueScore(),
                value.isSelected(),
                value.getStatus());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void extractAfterCommit(FileUpload file) {
        Runnable task = () -> extractionService.extractAsync(file.getFileMd5(), file.getUserId());
        afterCommit(task);
    }

    private void afterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            task.run();
                        }
                    });
        } else {
            task.run();
        }
    }

    public record DocumentGraphResponse(
            String fileMd5,
            boolean enabled,
            KnowledgeGraphStatus status,
            String error,
            Long templateId,
            String templateName,
            List<CandidateResponse> candidates,
            List<GraphNodeResponse> nodes,
            List<GraphEdgeResponse> edges,
            boolean neo4jEnabled,
            int batchChars,
            int chunkSize,
            GraphExtractionEngine.Progress progress) {}

    public record CandidateResponse(
            Long id,
            String subjectName,
            String subjectMentionName,
            String subjectType,
            String predicate,
            String objectName,
            String objectMentionName,
            String objectType,
            Integer evidenceChunkId,
            String evidenceText,
            Double confidence,
            Double valueScore,
            boolean selected,
            GraphCandidateStatus status) {}

    public record GraphNodeResponse(String id, String name, String type, int degree) {}

    public record GraphEdgeResponse(
            String id,
            String source,
            String target,
            String predicate,
            Double confidence,
            Integer evidenceChunkId,
            String evidenceText,
            GraphCandidateStatus status,
            String relationKind) {}

    public record CandidateUpdate(
            Boolean selected,
            String subjectName,
            String subjectType,
            String predicate,
            String objectName,
            String objectType) {}

    public record EnabledRequest(boolean enabled, Long templateId, Integer batchChars) {}

    public record RebuildRequest(Long templateId, Integer batchChars) {}

    private record GraphVisualization(
            List<GraphNodeResponse> nodes, List<GraphEdgeResponse> edges) {}

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
