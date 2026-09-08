package com.luky.nexusmind.repository;

import com.luky.nexusmind.model.UserModelQuota;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface UserModelQuotaRepository extends JpaRepository<UserModelQuota, Long> {
    Optional<UserModelQuota> findByUserId(Long userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<UserModelQuota> findForUpdateByUserId(Long userId);
}
