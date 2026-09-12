package com.luky.nexusmind.repository;

import com.luky.nexusmind.model.ChunkInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChunkInfoRepository extends JpaRepository<ChunkInfo, Long> {
    List<ChunkInfo> findByFileMd5AndUserIdOrderByChunkIndexAsc(String fileMd5, String userId);

    Optional<ChunkInfo> findByFileMd5AndUserIdAndChunkIndex(String fileMd5, String userId, int chunkIndex);
}
