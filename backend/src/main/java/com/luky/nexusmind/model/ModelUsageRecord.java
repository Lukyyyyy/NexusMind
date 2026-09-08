package com.luky.nexusmind.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "model_usage_records")
public class ModelUsageRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(nullable = false) private String username;
    @Column(name = "model_name", nullable = false, length = 160) private String modelName;
    @Enumerated(EnumType.STRING) @Column(name = "model_type", nullable = false, length = 16) private AiModelType modelType;
    @Column(nullable = false, length = 64) private String scenario;
    @Column(nullable = false, length = 16) private String status;
    @Column(name = "input_tokens", nullable = false) private long inputTokens;
    @Column(name = "cache_hit_tokens", nullable = false) private long cacheHitTokens;
    @Column(name = "output_tokens", nullable = false) private long outputTokens;
    @Column(name = "input_price", nullable = false, precision = 18, scale = 8) private BigDecimal inputPrice;
    @Column(name = "cache_hit_price", nullable = false, precision = 18, scale = 8) private BigDecimal cacheHitPrice;
    @Column(name = "output_price", nullable = false, precision = 18, scale = 8) private BigDecimal outputPrice;
    @Column(name = "reserved_amount", nullable = false, precision = 18, scale = 8) private BigDecimal reservedAmount;
    @Column(nullable = false, precision = 18, scale = 8) private BigDecimal amount = BigDecimal.ZERO;
    @CreationTimestamp @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "settled_at") private LocalDateTime settledAt;
}
