package com.luky.nexusmind.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "user_model_quotas")
public class UserModelQuota {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;
    @Column(name = "monthly_limit", nullable = false, precision = 18, scale = 8)
    private BigDecimal monthlyLimit = BigDecimal.ONE;
    @Column(name = "remaining_amount", nullable = false, precision = 18, scale = 8)
    private BigDecimal remainingAmount = BigDecimal.ONE;
    @Column(name = "monthly_reset", nullable = false)
    private boolean monthlyReset = true;
    @Column(name = "period_month", nullable = false)
    private LocalDate periodMonth;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
