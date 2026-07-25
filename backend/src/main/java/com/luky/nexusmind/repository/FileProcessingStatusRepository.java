package com.luky.nexusmind.repository;

import com.luky.nexusmind.model.FileProcessingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FileProcessingStatusRepository extends JpaRepository<FileProcessingStatus, Long> {
    Optional<FileProcessingStatus> findByFileMd5AndUserId(String fileMd5, String userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select status from FileProcessingStatus status where status.fileMd5 = :fileMd5 and status.userId = :userId")
    Optional<FileProcessingStatus> findByFileMd5AndUserIdForUpdate(@Param("fileMd5") String fileMd5,
                                                                   @Param("userId") String userId);

    List<FileProcessingStatus> findByFileMd5InAndUserId(Collection<String> fileMd5List, String userId);

    void deleteByFileMd5AndUserId(String fileMd5, String userId);
}
