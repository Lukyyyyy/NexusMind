package com.luky.nexusmind.im.service;

import com.luky.nexusmind.im.model.ImChannelType;
import com.luky.nexusmind.model.ChatSession;
import com.luky.nexusmind.model.ImConversation;
import com.luky.nexusmind.repository.ImConversationRepository;
import com.luky.nexusmind.service.ChatSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/** 平台会话 -> ChatSession 的映射维护；IM 问答历史与 Web 端共用同一套会话体系。 */
@Service
public class ImConversationService {

    private static final Logger logger = LoggerFactory.getLogger(ImConversationService.class);

    private final ImConversationRepository conversationRepository;
    private final ChatSessionService chatSessionService;

    public ImConversationService(ImConversationRepository conversationRepository,
                                 ChatSessionService chatSessionService) {
        this.conversationRepository = conversationRepository;
        this.chatSessionService = chatSessionService;
    }

    @Transactional
    public Long resolveChatSessionId(Long userId, ImChannelType channel, String platformConversationId,
                                     String username) {
        Optional<ImConversation> existing = conversationRepository
                .findByUserIdAndChannelTypeAndPlatformConversationId(userId, channel, platformConversationId);
        if (existing.isPresent()) {
            ImConversation link = existing.get();
            try {
                chatSessionService.getOwnedActiveSession(username, link.getChatSessionId());
                link.setLastActiveAt(LocalDateTime.now());
                conversationRepository.save(link);
                return link.getChatSessionId();
            } catch (RuntimeException e) {
                logger.info("IM 会话映射指向的聊天会话已失效，将重建: {}", e.getMessage());
            }
        }
        ChatSession session = chatSessionService.createSession(username);
        ImConversation link = existing.orElseGet(ImConversation::new);
        link.setUserId(userId);
        link.setChannelType(channel);
        link.setPlatformConversationId(platformConversationId);
        link.setChatSessionId(session.getId());
        link.setLastActiveAt(LocalDateTime.now());
        conversationRepository.save(link);
        return session.getId();
    }
}
