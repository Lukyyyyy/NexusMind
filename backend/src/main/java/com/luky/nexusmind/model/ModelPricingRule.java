package com.luky.nexusmind.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "model_pricing_rules")
public class ModelPricingRule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "model_name", nullable = false, unique = true, length = 160)
    private String modelName;
    @Enumerated(EnumType.STRING) @Column(name = "model_type", nullable = false, length = 16)
    private AiModelType modelType;
    @Column(nullable = false) private boolean enabled = true;
    @Column(name = "input_price", nullable = false, precision = 18, scale = 8)
    private BigDecimal inputPrice = BigDecimal.ZERO;
    @Column(name = "cache_hit_price", nullable = false, precision = 18, scale = 8)
    private BigDecimal cacheHitPrice = BigDecimal.ZERO;
    @Column(name = "output_price", nullable = false, precision = 18, scale = 8)
    private BigDecimal outputPrice = BigDecimal.ZERO;
    @Column(name = "off_peak_input_price", precision = 18, scale = 8)
    private BigDecimal offPeakInputPrice;
    @Column(name = "off_peak_cache_hit_price", precision = 18, scale = 8)
    private BigDecimal offPeakCacheHitPrice;
    @Column(name = "off_peak_output_price", precision = 18, scale = 8)
    private BigDecimal offPeakOutputPrice;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
