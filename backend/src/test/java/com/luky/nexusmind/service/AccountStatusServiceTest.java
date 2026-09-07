package com.luky.nexusmind.service;

import com.luky.nexusmind.handler.ChatWebSocketHandler;
import com.luky.nexusmind.handler.NotificationWebSocketHandler;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.UserRepository;
import com.luky.nexusmind.utils.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountStatusServiceTest {
    @Test
    void disablingUserChangesStateAndRevokesSessionsAfterCommit() {
        UserRepository users = mock(UserRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        MailService mail = mock(MailService.class);
        AuditService audit = mock(AuditService.class);
        JwtUtils jwt = mock(JwtUtils.class);
        ChatWebSocketHandler chat = mock(ChatWebSocketHandler.class);
        NotificationWebSocketHandler sockets = mock(NotificationWebSocketHandler.class);
        AccountStatusService service = new AccountStatusService(users, notifications, mail, audit, jwt, chat, sockets);
        User actor = user(1L, "root", User.Role.SUPER_ADMIN);
        User target = user(2L, "member", User.Role.USER);
        when(users.findById(2L)).thenReturn(Optional.of(target));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.change(actor, 2L, false, "账户风险", "127.0.0.1");
            assertFalse(target.isEnabled());
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(jwt).invalidateAllUserTokens("2");
        verify(chat).disconnectUser("member");
        verify(sockets).notifyDisabledAndDisconnect(2L);
        verify(mail).enqueueAccountStatusChanged(eq(target), eq("被禁用"), anyString(), eq("账户风险"));
        verify(audit).record(actor, "ACCOUNT_DISABLED", 2L, null, "账户风险", "127.0.0.1");
    }

    private User user(Long id, String username, User.Role role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        return user;
    }
}
