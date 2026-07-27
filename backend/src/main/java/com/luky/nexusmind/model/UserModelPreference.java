package com.luky.nexusmind.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "user_model_preferences", uniqueConstraints = @UniqueConstraint(columnNames = "user_id"))
public class UserModelPreference {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "llm_config_id")
    private Long llmConfigId;

    @Column(name = "embedding_config_id")
    private Long embeddingConfigId;

    /** Null means that graph extraction follows the selected chat model. */
    @Column(name = "graph_extraction_config_id")
    private Long graphExtractionConfigId;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
