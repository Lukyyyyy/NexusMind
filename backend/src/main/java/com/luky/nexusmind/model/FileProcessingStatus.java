package com.luky.nexusmind.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "file_processing_status",
        uniqueConstraints = @UniqueConstraint(name = "uk_file_processing_file_user", columnNames = {"file_md5", "user_id"})
)
public class FileProcessingStatus {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_md5", length = 32, nullable = false)
    private String fileMd5;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "parse_engine", length = 16, nullable = false)
    private ParseEngine parseEngine = ParseEngine.AUTO;

    @Enumerated(EnumType.STRING)
    @Column(name = "actual_parse_engine", length = 16)
    private ParseEngine actualParseEngine;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_stage", length = 32, nullable = false)
    private ProcessingStage currentStage = ProcessingStage.QUEUED;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 16, nullable = false)
    private ProcessingState state = ProcessingState.PENDING;

    @Column(name = "message", length = 255)
    private String message;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "parsed_chunk_count")
    private Integer parsedChunkCount = 0;

    @Column(name = "chunk_size")
    private Integer chunkSize;

    @Column(name = "vectorized_count")
    private Integer vectorizedCount = 0;

    @Column(name = "es_document_count")
    private Long esDocumentCount = 0L;

    @Column(name = "processing_started_at")
    private LocalDateTime processingStartedAt;

    @Column(name = "accumulated_processing_duration_millis")
    private Long accumulatedProcessingDurationMillis = 0L;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
