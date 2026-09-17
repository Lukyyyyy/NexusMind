package com.luky.nexusmind.repository;

import com.luky.nexusmind.im.model.ImChannelType;
import com.luky.nexusmind.model.ImMessageLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ImMessageLogRepository extends JpaRepository<ImMessageLog, Long> {
    Optional<ImMessageLog> findByChannelTypeAndDirectionAndPlatformMsgId(ImChannelType channelType,
                                                                          ImMessageLog.Direction direction,
                                                                          String platformMsgId);
}
