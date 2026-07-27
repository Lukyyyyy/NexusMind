package com.luky.nexusmind.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "graph_candidates", indexes = {
        @Index(name = "idx_graph_candidate_file", columnList = "file_upload_id"),
        @Index(name = "idx_graph_candidate_status", columnList = "status")
})
public class GraphCandidate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_upload_id", nullable = false)
    private Long fileUploadId;

    @Column(name = "subject_name", nullable = false, length = 255)
    private String subjectName;

    @Column(name = "subject_type", nullable = false, length = 64)
    private String subjectType;

    @Column(name = "predicate_name", nullable = false, length = 128)
    private String predicate;

    @Column(name = "object_name", nullable = false, length = 255)
    private String objectName;

    @Column(name = "object_type", nullable = false, length = 64)
    private String objectType;

    @Column(name = "evidence_chunk_id", nullable = false)
    private Integer evidenceChunkId;

    @Column(name = "evidence_text", nullable = false, columnDefinition = "TEXT")
    private String evidenceText;

    private Double confidence;

    @Column(nullable = false)
    private boolean selected = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GraphCandidateStatus status = GraphCandidateStatus.PENDING;

    @Column(name = "model_name", length = 160)
    private String modelName;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
