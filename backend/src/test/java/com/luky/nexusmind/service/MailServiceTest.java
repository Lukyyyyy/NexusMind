package com.luky.nexusmind.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luky.nexusmind.model.EmailDelivery;
import com.luky.nexusmind.model.SmtpSettings;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.EmailDeliveryRepository;
import com.luky.nexusmind.repository.SmtpSettingsRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MailServiceTest {
    private final MailService service = new MailService(
            null, null, null,
            new ObjectMapper(), "tencent-ses", "", "", "ap-hongkong", "noreply@example.com",
            1, 2, 3, 4, 5, 6, 7, 8);

    @Test
    void readsTemplateVariables() throws Exception {
        EmailDelivery verification = new EmailDelivery();
        verification.setTemplateKind(EmailDelivery.TemplateKind.VERIFICATION);
        verification.setBody("{\"code\":\"012345\",\"minutes\":\"10\"}");
        assertThat(service.templateData(verification))
                .containsEntry("code", "012345")
                .containsEntry("minutes", "10");

        EmailDelivery application = new EmailDelivery();
        application.setTemplateKind(EmailDelivery.TemplateKind.ORGANIZATION_APPLICATION);
        application.setBody("{\"applicant\":\"张三\",\"organization\":\"研发部\",\"reason\":\"项目协作\"}");
        assertThat(service.templateData(application))
                .containsEntry("applicant", "张三")
                .containsEntry("organization", "研发部")
                .containsEntry("reason", "项目协作");

    }

    @Test
    void includesEscapedDisplayNameInAccountStatusEmail() throws Exception {
        AtomicReference<EmailDelivery> saved = new AtomicReference<>();
        EmailDeliveryRepository repository = (EmailDeliveryRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{EmailDeliveryRepository.class},
                (proxy, method, args) -> method.getName().equals("save") ? saved.getAndSet((EmailDelivery) args[0]) : null);
        MailService mail = new MailService(repository, null, null,
                new ObjectMapper(), "tencent-ses", "", "", "ap-hongkong", "noreply@example.com",
                1, 2, 3, 4, 5, 6, 7, 8);
        User recipient = new User();
        recipient.setUsername("zhangsan");
        recipient.setDisplayName("张三<script>");
        recipient.setEmail("zhangsan@example.com");
        recipient.setEmailVerifiedAt(LocalDateTime.now());

        mail.enqueueAccountStatusChanged(recipient, "被禁用", "2026-09-07 12:00:00", "账户风险");

        assertThat(mail.templateData(saved.get()))
                .containsEntry("displayName", "张三&lt;script&gt;")
                .containsEntry("action", "被禁用")
                .containsEntry("reason", "账户风险");
    }

    @Test
    void respectsGlobalServiceSwitch() {
        SmtpSettings settings = new SmtpSettings();
        settings.setEnabled(false);
        SmtpSettingsRepository repository = (SmtpSettingsRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{SmtpSettingsRepository.class},
                (proxy, method, args) -> method.getName().equals("findById") ? Optional.of(settings) : null);
        MailService disabled = new MailService(
                null, repository, null, new ObjectMapper(), "tencent-ses", "", "", "ap-hongkong", "noreply@example.com",
                1, 2, 3, 4, 5, 6, 7, 8);

        assertThat(disabled.isEnabled()).isFalse();
    }
}
