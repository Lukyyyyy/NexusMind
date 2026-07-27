package com.luky.nexusmind.service;

import com.luky.nexusmind.client.KnowledgeGraphExtractionClient;
import com.luky.nexusmind.model.*;
import com.luky.nexusmind.repository.DocumentVectorRepository;
import com.luky.nexusmind.repository.FileUploadRepository;
import com.luky.nexusmind.repository.GraphCandidateRepository;
import com.luky.nexusmind.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class KnowledgeGraphExtractionService {
    private static final Logger logger = LoggerFactory.getLogger(KnowledgeGraphExtractionService.class);
    private static final int MAX_BATCH_CHARS = 6000;
    private static final double MIN_CONFIDENCE = 0.60;

    private final FileUploadRepository fileUploadRepository;
    private final DocumentVectorRepository documentVectorRepository;
    private final GraphCandidateRepository candidateRepository;
    private final UserRepository userRepository;
    private final KnowledgeGraphExtractionClient extractionClient;

    public KnowledgeGraphExtractionService(FileUploadRepository fileUploadRepository,
                                           DocumentVectorRepository documentVectorRepository,
                                           GraphCandidateRepository candidateRepository,
                                           UserRepository userRepository,
                                           KnowledgeGraphExtractionClient extractionClient) {
        this.fileUploadRepository = fileUploadRepository;
        this.documentVectorRepository = documentVectorRepository;
        this.candidateRepository = candidateRepository;
        this.userRepository = userRepository;
        this.extractionClient = extractionClient;
    }

    @Async
    public void extractAsync(String fileMd5, String ownerId) {
        try {
            extract(fileMd5, ownerId);
        } catch (Exception e) {
            logger.error("知识图谱抽取失败，fileMd5={}", fileMd5, e);
        }
    }

    @Transactional
    public void extract(String fileMd5, String ownerId) {
        FileUpload file = fileUploadRepository.findByFileMd5AndUserId(fileMd5, ownerId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在"));
        if (!file.isGraphEnabled()) return;
        file.setGraphStatus(KnowledgeGraphStatus.EXTRACTING);
        file.setGraphError(null);
        fileUploadRepository.save(file);
        candidateRepository.deleteByFileUploadId(file.getId());

        try {
            List<DocumentVector> chunks = distinctChunks(documentVectorRepository.findByFileMd5(fileMd5));
            if (chunks.isEmpty()) throw new IllegalStateException("文档尚未完成解析");
            String username = resolveUsername(file.getUserId());
            int saved = 0;
            for (List<DocumentVector> batch : batches(chunks)) {
                String input = buildInput(batch);
                KnowledgeGraphExtractionClient.ExtractionResult result = extractionClient.extract(username, input);
                Map<Integer, String> evidenceByChunk = new HashMap<>();
                batch.forEach(chunk -> evidenceByChunk.put(chunk.getChunkId(), chunk.getTextContent()));
                for (KnowledgeGraphExtractionClient.ExtractedRelation relation : result.relations()) {
                    GraphCandidate candidate = toCandidate(file, relation, evidenceByChunk, result.modelName());
                    if (candidate != null) {
                        candidateRepository.save(candidate);
                        saved++;
                    }
                }
            }
            file.setGraphStatus(KnowledgeGraphStatus.PENDING_REVIEW);
            file.setGraphError(saved == 0 ? "未从文档中识别到可靠关系" : null);
            fileUploadRepository.save(file);
        } catch (Exception e) {
            file.setGraphStatus(KnowledgeGraphStatus.FAILED);
            file.setGraphError(abbreviate(e.getMessage(), 1000));
            fileUploadRepository.save(file);
            throw e;
        }
    }

    private GraphCandidate toCandidate(FileUpload file,
                                       KnowledgeGraphExtractionClient.ExtractedRelation value,
                                       Map<Integer, String> evidenceByChunk,
                                       String modelName) {
        if (value == null || value.subject() == null || value.object() == null
                || !hasText(value.subject().name()) || !hasText(value.object().name())
                || !hasText(value.predicate()) || value.chunkId() == null
                || !evidenceByChunk.containsKey(value.chunkId())) return null;
        double confidence = value.confidence() == null ? 0.0 : value.confidence();
        if (confidence < MIN_CONFIDENCE) return null;
        String source = evidenceByChunk.get(value.chunkId());
        String evidence = hasText(value.evidence()) && source.contains(value.evidence().trim())
                ? value.evidence().trim()
                : abbreviate(source, 600);
        GraphCandidate candidate = new GraphCandidate();
        candidate.setFileUploadId(file.getId());
        candidate.setSubjectName(value.subject().name().trim());
        candidate.setSubjectType(normalizeType(value.subject().type()));
        candidate.setPredicate(value.predicate().trim());
        candidate.setObjectName(value.object().name().trim());
        candidate.setObjectType(normalizeType(value.object().type()));
        candidate.setEvidenceChunkId(value.chunkId());
        candidate.setEvidenceText(evidence);
        candidate.setConfidence(Math.min(1.0, confidence));
        candidate.setSelected(true);
        candidate.setStatus(GraphCandidateStatus.PENDING);
        candidate.setModelName(modelName);
        return candidate;
    }

    private List<DocumentVector> distinctChunks(List<DocumentVector> values) {
        Map<Integer, DocumentVector> byId = new TreeMap<>();
        values.forEach(value -> byId.putIfAbsent(value.getChunkId(), value));
        return new ArrayList<>(byId.values());
    }

    private List<List<DocumentVector>> batches(List<DocumentVector> chunks) {
        List<List<DocumentVector>> result = new ArrayList<>();
        List<DocumentVector> current = new ArrayList<>();
        int chars = 0;
        for (DocumentVector chunk : chunks) {
            int size = chunk.getTextContent() == null ? 0 : chunk.getTextContent().length();
            if (!current.isEmpty() && chars + size > MAX_BATCH_CHARS) {
                result.add(current);
                current = new ArrayList<>();
                chars = 0;
            }
            current.add(chunk);
            chars += size;
        }
        if (!current.isEmpty()) result.add(current);
        return result;
    }

    private String buildInput(List<DocumentVector> chunks) {
        StringBuilder value = new StringBuilder("请从以下文档切片抽取实体关系：\n\n");
        for (DocumentVector chunk : chunks) {
            value.append("[CHUNK ").append(chunk.getChunkId()).append("]\n")
                    .append(chunk.getTextContent()).append("\n\n");
        }
        return value.toString();
    }

    private String resolveUsername(String ownerId) {
        Optional<User> user = userRepository.findByUsername(ownerId);
        if (user.isEmpty()) {
            try { user = userRepository.findById(Long.parseLong(ownerId)); }
            catch (NumberFormatException ignored) { }
        }
        return user.map(User::getUsername).orElse(ownerId);
    }

    private String normalizeType(String value) {
        return hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "OTHER";
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private String abbreviate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
