package com.luky.nexusmind.repository;

import com.luky.nexusmind.model.ChatMessage;
import com.luky.nexusmind.model.ChatSession;
import com.luky.nexusmind.model.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.transaction.TransactionalTestExecutionListener;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"})
@TestExecutionListeners(listeners = {DependencyInjectionTestExecutionListener.class,
        TransactionalTestExecutionListener.class}, mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class ChatSessionRepositoryTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ChatSessionRepository sessions;

    @Test
    void historyUsesLastConversationTimeEvenWhenSessionWasModifiedLater() {
        User user = new User();
        user.setUsername("alice");
        user.setPassword("test");
        user.setRole(User.Role.USER);
        entityManager.persist(user);

        ChatSession older = session(user, "旧会话");
        ChatSession newer = session(user, "新会话标题");
        ChatMessage oldMessage = message(older);
        ChatMessage newMessage = message(newer);
        entityManager.flush();

        LocalDateTime august = LocalDateTime.of(2026, 8, 25, 22, 59);
        LocalDateTime september = LocalDateTime.of(2026, 9, 3, 19, 34);
        entityManager.createNativeQuery("update chat_messages set created_at = :time where id = :id")
                .setParameter("time", august).setParameter("id", oldMessage.getId()).executeUpdate();
        entityManager.createNativeQuery("update chat_messages set created_at = :time where id = :id")
                .setParameter("time", september).setParameter("id", newMessage.getId()).executeUpdate();
        entityManager.createNativeQuery("update chat_sessions set updated_at = :time where id = :id")
                .setParameter("time", LocalDateTime.of(2026, 9, 23, 12, 0))
                .setParameter("id", older.getId()).executeUpdate();
        entityManager.clear();

        List<ChatSession> history = sessions.findHistoryByUsername("alice");
        assertEquals(List.of(newer.getId(), older.getId()), history.stream().map(ChatSession::getId).toList());
        assertEquals(august, history.get(1).getLastConversationAt());
        assertEquals(LocalDateTime.of(2026, 9, 23, 12, 0), history.get(1).getUpdatedAt());
    }

    private ChatSession session(User user, String title) {
        ChatSession session = new ChatSession();
        session.setUser(user);
        session.setTitle(title);
        entityManager.persist(session);
        return session;
    }

    private ChatMessage message(ChatSession session) {
        ChatMessage message = new ChatMessage();
        message.setSession(session);
        message.setRole("assistant");
        message.setContent("回答");
        message.setStatus("finished");
        entityManager.persist(message);
        return message;
    }
}
