package com.luky.nexusmind.repository;

import com.luky.nexusmind.im.model.ImChannelType;
import com.luky.nexusmind.model.ImChannelConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ImChannelConfigRepository extends JpaRepository<ImChannelConfig, ImChannelConfig.CompositeKey> {
    Optional<ImChannelConfig> findByChannelTypeAndUserId(ImChannelType channelType, Long userId);
    Optional<ImChannelConfig> findByChannelTypeAndBotId(ImChannelType channelType, String botId);
    List<ImChannelConfig> findByUserId(Long userId);
    List<ImChannelConfig> findByEnabled(boolean enabled);
}
