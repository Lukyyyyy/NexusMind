package com.luky.nexusmind.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "ai_model_configs")
public class AiModelConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AiModelOwnerType ownerType;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AiModelType modelType;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(length = 80)
    private String provider;

    @Column(nullable = false, length = 512)
    private String baseUrl;

    @Lob
    private String apiKeyEncrypted;

    @Column(nullable = false, length = 160)
    private String modelName;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private boolean defaultModel = false;

    private Double temperature;

    private Double topP;

    private Integer maxTokens;

    private Integer dimension;

    private Integer batchSize;

    private Integer maxConcurrency;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
