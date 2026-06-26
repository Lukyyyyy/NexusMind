package com.luky.nexusmind.repository;

import com.luky.nexusmind.model.AiModelConfig;
import com.luky.nexusmind.model.AiModelOwnerType;
import com.luky.nexusmind.model.AiModelType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiModelConfigRepository extends JpaRepository<AiModelConfig, Long> {
    Optional<AiModelConfig> findFirstByOwnerTypeAndModelTypeAndDefaultModelTrueAndEnabledTrue(
            AiModelOwnerType ownerType,
            AiModelType modelType);

    List<AiModelConfig> findByOwnerTypeAndEnabledTrue(AiModelOwnerType ownerType);

    List<AiModelConfig> findByOwnerTypeAndOwnerUserId(AiModelOwnerType ownerType, Long ownerUserId);

    List<AiModelConfig> findByOwnerType(AiModelOwnerType ownerType);

    List<AiModelConfig> findByOwnerTypeAndModelType(AiModelOwnerType ownerType, AiModelType modelType);
}
