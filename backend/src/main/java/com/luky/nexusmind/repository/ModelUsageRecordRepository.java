package com.luky.nexusmind.repository;

import com.luky.nexusmind.model.ModelUsageRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ModelUsageRecordRepository extends JpaRepository<ModelUsageRecord, Long> {
    List<ModelUsageRecord> findByCreatedAtBetweenOrderByCreatedAtDesc(LocalDateTime from, LocalDateTime to);
    List<ModelUsageRecord> findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(Long userId, LocalDateTime from, LocalDateTime to);
    List<ModelUsageRecord> findByUserIdAndStatusAndCreatedAtBefore(Long userId, String status, LocalDateTime before);
}
