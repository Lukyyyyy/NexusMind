package com.luky.nexusmind.service;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.handler.ChatWebSocketHandler;
import com.luky.nexusmind.handler.NotificationWebSocketHandler;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.UserRepository;
import com.luky.nexusmind.utils.JwtUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.util.HtmlUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class AccountStatusService {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final MailService mailService;
    private final AuditService auditService;
    private final JwtUtils jwtUtils;
    private final ChatWebSocketHandler chatSocket;
    private final NotificationWebSocketHandler notificationSocket;

    public AccountStatusService(UserRepository userRepository, NotificationService notificationService,
                                MailService mailService, AuditService auditService, JwtUtils jwtUtils,
                                ChatWebSocketHandler chatSocket, NotificationWebSocketHandler notificationSocket) {
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.mailService = mailService;
        this.auditService = auditService;
        this.jwtUtils = jwtUtils;
        this.chatSocket = chatSocket;
        this.notificationSocket = notificationSocket;
    }

    @Transactional
    public void change(User actor, Long userId, boolean enabled, String reason, String ip) {
        if (actor.getRole() != User.Role.SUPER_ADMIN) throw new CustomException("需要超级管理员权限", HttpStatus.FORBIDDEN);
        if (actor.getId().equals(userId)) throw new CustomException("不能修改自己的账户状态", HttpStatus.CONFLICT);
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
        if (target.getRole() == User.Role.SUPER_ADMIN) throw new CustomException("不能修改超级管理员的账户状态", HttpStatus.CONFLICT);
        if (target.isEnabled() == enabled) throw new CustomException("账户已处于目标状态", HttpStatus.CONFLICT);

        String cleanReason = validateReason(reason);
        LocalDateTime changedAt = LocalDateTime.now();
        target.setEnabled(enabled);
        userRepository.save(target);

        String action = enabled ? "重新启用" : "被禁用";
        notificationService.notify(target, enabled ? "ACCOUNT_ENABLED" : "ACCOUNT_DISABLED",
                enabled ? "账户已重新启用" : "账户已被禁用",
                "你的账户已" + action + "。原因：" + cleanReason, null);
        mailService.enqueueAccountStatusChanged(target, action, changedAt.format(TIME_FORMAT),
                HtmlUtils.htmlEscape(cleanReason));
        auditService.record(actor, enabled ? "ACCOUNT_ENABLED" : "ACCOUNT_DISABLED",
                target.getId(), null, cleanReason, ip);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                jwtUtils.invalidateAllUserTokens(target.getId().toString());
                if (!enabled) {
                    chatSocket.disconnectUser(target.getUsername());
                    notificationSocket.notifyDisabledAndDisconnect(target.getId());
                }
            }
        });
    }

    private String validateReason(String value) {
        String clean = value == null ? "" : value.trim();
        int length = clean.codePointCount(0, clean.length());
        if (length < 2 || length > 200 || clean.codePoints().anyMatch(Character::isISOControl)) {
            throw new CustomException("变更原因需为 2–200 个字符且不能包含控制字符", HttpStatus.BAD_REQUEST);
        }
        return clean;
    }
}
