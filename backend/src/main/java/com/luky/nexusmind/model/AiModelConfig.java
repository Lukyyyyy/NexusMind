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

    private Integer maxToolCalls;

    private Integer dimension;

    private Integer batchSize;

    private Integer maxConcurrency;

    /** RERANK：自定义排序任务指令（DashScope instruct 参数，建议英文；空 = 使用服务端默认指令） */
    @Column(length = 2000)
    private String instruct;

    /** RERANK：重排候选窗口（送入重排模型的候选条数，1~100）；空 = 使用全局 ai.retrieval.rerank-top-n */
    private Integer topN;

    /** RERANK：视频抽帧比例 fps（0~1，仅重排视频文档时生效） */
    private Double fps;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
