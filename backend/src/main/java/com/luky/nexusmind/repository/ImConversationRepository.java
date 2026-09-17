package com.luky.nexusmind.repository;

import com.luky.nexusmind.im.model.ImChannelType;
import com.luky.nexusmind.model.ImConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ImConversationRepository extends JpaRepository<ImConversation, Long> {
    Optional<ImConversation> findByUserIdAndChannelTypeAndPlatformConversationId(Long userId, ImChannelType channelType,
                                                                        String platformConversationId);

    List<ImConversation> findByUserId(Long userId);
}
