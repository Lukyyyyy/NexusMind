package com.luky.nexusmind.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luky.nexusmind.model.EmailDelivery;
import com.luky.nexusmind.model.SmtpSettings;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.EmailDeliveryRepository;
import com.luky.nexusmind.repository.SmtpSettingsRepository;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.ses.v20201002.SesClient;
import com.tencentcloudapi.ses.v20201002.models.SendEmailRequest;
import com.tencentcloudapi.ses.v20201002.models.Template;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Service
public class MailService {
    private static final List<Integer> RETRY_MINUTES = List.of(1, 5, 30);
    private final EmailDeliveryRepository deliveryRepository;
    private final SmtpSettingsRepository settingsRepository;
    private final SmtpCryptoService cryptoService;
    private final ObjectMapper objectMapper;
    private final String provider;
    private final String sesRegion;
    private final String sesFromAddress;
    private final long verificationTemplateId;
    private final Map<EmailDelivery.TemplateKind, Long> sesTemplateIds;
    private final SesClient sesClient;

    public MailService(EmailDeliveryRepository deliveryRepository, SmtpSettingsRepository settingsRepository,
                       SmtpCryptoService cryptoService, ObjectMapper objectMapper,
                       @Value("${mail.provider:smtp}") String provider,
                       @Value("${mail.tencent-ses.secret-id:}") String secretId,
                       @Value("${mail.tencent-ses.secret-key:}") String secretKey,
                       @Value("${mail.tencent-ses.region:ap-hongkong}") String sesRegion,
                       @Value("${mail.tencent-ses.from-address:}") String sesFromAddress,
                       @Value("${mail.tencent-ses.verification-template-id:0}") long verificationTemplateId,
                       @Value("${mail.tencent-ses.organization-application-template-id:0}") long organizationApplicationTemplateId,
                       @Value("${mail.tencent-ses.organization-result-template-id:0}") long organizationResultTemplateId,
                       @Value("${mail.tencent-ses.membership-change-template-id:0}") long membershipChangeTemplateId,
                       @Value("${mail.tencent-ses.role-change-template-id:0}") long roleChangeTemplateId,
                       @Value("${mail.tencent-ses.account-status-changed-template-id:0}") long accountStatusChangedTemplateId,
                       @Value("${mail.tencent-ses.email-changed-template-id:0}") long emailChangedTemplateId,
                       @Value("${mail.tencent-ses.test-template-id:0}") long testTemplateId) {
        this.deliveryRepository = deliveryRepository;
        this.settingsRepository = settingsRepository;
        this.cryptoService = cryptoService;
        this.objectMapper = objectMapper;
        this.provider = provider.trim().toLowerCase();
        this.sesRegion = sesRegion.trim();
        this.sesFromAddress = sesFromAddress.trim();
        this.verificationTemplateId = verificationTemplateId;
        this.sesTemplateIds = Map.of(
                EmailDelivery.TemplateKind.ORGANIZATION_APPLICATION, organizationApplicationTemplateId,
                EmailDelivery.TemplateKind.ORGANIZATION_RESULT, organizationResultTemplateId,
                EmailDelivery.TemplateKind.MEMBERSHIP_CHANGE, membershipChangeTemplateId,
                EmailDelivery.TemplateKind.ROLE_CHANGE, roleChangeTemplateId,
                EmailDelivery.TemplateKind.ACCOUNT_STATUS_CHANGED, accountStatusChangedTemplateId,
                EmailDelivery.TemplateKind.EMAIL_CHANGED, emailChangedTemplateId,
                EmailDelivery.TemplateKind.TEST, testTemplateId);
        this.sesClient = secretId.isBlank() || secretKey.isBlank()
                ? null : new SesClient(new Credential(secretId.trim(), secretKey.trim()), this.sesRegion);
    }

    private void enqueue(User user, EmailDelivery.TemplateKind kind, String subject,
                         Map<String, String> variables, boolean securityMessage) {
        if (user.getEmailVerifiedAt() == null || user.getEmail() == null) return;
        if (!securityMessage && !user.isOrganizationEmailEnabled()) return;
        enqueue(user.getEmail(), kind, subject, variables);
    }

    private void enqueue(String recipient, EmailDelivery.TemplateKind kind, String subject,
                         Map<String, String> variables) {
        EmailDelivery delivery = new EmailDelivery();
        delivery.setRecipient(recipient);
        delivery.setSubject(subject);
        delivery.setBody(json(variables));
        delivery.setTemplateKind(kind);
        deliveryRepository.save(delivery);
    }

    public void enqueueVerification(String recipient, String code) {
        enqueue(recipient, EmailDelivery.TemplateKind.VERIFICATION, "知枢 NexusMind 邮箱验证码",
                Map.of("code", code, "minutes", "10"));
    }

    public void enqueueOrganizationApplication(User recipient, String applicant, String organization, String reason) {
        enqueue(recipient, EmailDelivery.TemplateKind.ORGANIZATION_APPLICATION, "新的组织加入申请",
                Map.of("applicant", applicant, "organization", organization, "reason", reason), false);
    }

    public void enqueueOrganizationResult(User recipient, String organization, String result, String reason) {
        enqueue(recipient, EmailDelivery.TemplateKind.ORGANIZATION_RESULT, "组织加入申请已" + result,
                Map.of("organization", organization, "result", result, "reason", reason), false);
    }

    public void enqueueMembershipChange(User recipient, String reason) {
        enqueue(recipient, EmailDelivery.TemplateKind.MEMBERSHIP_CHANGE, "组织成员关系已变更",
                Map.of("reason", reason), false);
    }

    public void enqueueRoleChange(User recipient, String action) {
        enqueue(recipient, EmailDelivery.TemplateKind.ROLE_CHANGE, "知枢 NexusMind 账号角色已变更",
                Map.of("action", action), true);
    }

    public void enqueueAccountStatusChanged(User recipient, String action, String time, String reason) {
        String displayName = recipient.getDisplayName() == null || recipient.getDisplayName().isBlank()
                ? recipient.getUsername() : recipient.getDisplayName();
        enqueue(recipient, EmailDelivery.TemplateKind.ACCOUNT_STATUS_CHANGED,
                "知枢 NexusMind 账户状态已变更",
                Map.of("displayName", HtmlUtils.htmlEscape(displayName),
                        "action", action, "time", time, "reason", reason), true);
    }

    public void enqueueEmailChanged(String recipient) {
        enqueue(recipient, EmailDelivery.TemplateKind.EMAIL_CHANGED, "知枢 NexusMind 登录邮箱已变更", Map.of());
    }

    public boolean isConfigured() {
        if (usesTencentSes()) {
            return sesClient != null && !sesFromAddress.isBlank()
                    && verificationTemplateId > 0 && sesTemplateIds.values().stream().allMatch(id -> id > 0);
        }
        return settingsRepository.findById(1L).filter(settings -> !settings.getHost().isBlank()
                && !settings.getUsername().isBlank() && !settings.getEncryptedPassword().isBlank()
                && !settings.getFromAddress().isBlank()).isPresent();
    }

    public boolean isEnabled() {
        return settingsRepository.findById(1L).map(SmtpSettings::isEnabled).orElse(true);
    }

    public void setEnabled(boolean enabled) {
        SmtpSettings settings = settingsRepository.findById(1L).orElseGet(() -> {
            SmtpSettings value = new SmtpSettings();
            value.setHost(""); value.setUsername(""); value.setEncryptedPassword(""); value.setFromAddress("");
            return value;
        });
        settings.setEnabled(enabled);
        settingsRepository.save(settings);
    }

    public boolean usesTencentSes() { return "tencent-ses".equals(provider); }
    public String provider() { return provider; }
    public String region() { return sesRegion; }
    public String fromAddress() { return sesFromAddress; }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void sendPending() {
        if (!isEnabled()) return;
        deliveryRepository.findTop50ByStatusAndNextAttemptAtBeforeOrderByCreatedAtAsc(
                EmailDelivery.Status.PENDING, LocalDateTime.now()).forEach(this::attempt);
    }

    public void sendTest(String recipient) {
        if (!isEnabled()) throw new IllegalStateException("邮件服务已停用");
        EmailDelivery value = new EmailDelivery();
        value.setRecipient(recipient);
        value.setSubject("知枢 NexusMind 邮件服务测试");
        value.setBody("{}");
        value.setTemplateKind(EmailDelivery.TemplateKind.TEST);
        attempt(value);
        if (value.getStatus() != EmailDelivery.Status.SENT) throw new IllegalStateException(value.getLastError());
    }

    private void attempt(EmailDelivery delivery) {
        try {
            if (usesTencentSes()) sendTencentSes(delivery);
            else sendSmtp(delivery);
            delivery.setStatus(EmailDelivery.Status.SENT);
            delivery.setLastError(null);
        } catch (Exception e) {
            delivery.setAttempts(delivery.getAttempts() + 1);
            delivery.setLastError(shortMessage(e));
            if (delivery.getAttempts() > RETRY_MINUTES.size()) delivery.setStatus(EmailDelivery.Status.FAILED);
            else delivery.setNextAttemptAt(LocalDateTime.now().plusMinutes(RETRY_MINUTES.get(delivery.getAttempts() - 1)));
        }
        deliveryRepository.save(delivery);
    }

    private void sendTencentSes(EmailDelivery delivery) throws Exception {
        if (sesClient == null || sesFromAddress.isBlank()) throw new IllegalStateException("腾讯云 SES 尚未配置完整");
        SendEmailRequest request = new SendEmailRequest();
        request.setFromEmailAddress(sesFromAddress);
        request.setDestination(new String[]{delivery.getRecipient()});
        request.setSubject(delivery.getSubject());
        Template template = new Template();
        Long templateId = delivery.getTemplateKind() == EmailDelivery.TemplateKind.VERIFICATION
                ? verificationTemplateId : sesTemplateIds.get(delivery.getTemplateKind());
        if (templateId == null || templateId <= 0) throw new IllegalStateException("邮件模板 ID 未配置");
        template.setTemplateID(templateId);
        template.setTemplateData(delivery.getBody());
        request.setTemplate(template);
        sesClient.SendEmail(request);
    }

    private String json(Map<String, String> variables) {
        try {
            return objectMapper.writeValueAsString(variables);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("邮件模板变量序列化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, String> templateData(EmailDelivery delivery) throws JsonProcessingException {
        return objectMapper.readValue(delivery.getBody(), Map.class);
    }

    private void sendSmtp(EmailDelivery delivery) throws Exception {
        SmtpSettings settings = settingsRepository.findById(1L).filter(SmtpSettings::isEnabled)
                .orElseThrow(() -> new IllegalStateException("邮件服务尚未配置"));
        JavaMailSenderImpl sender = sender(settings);
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
        helper.setFrom(settings.getFromAddress());
        helper.setTo(delivery.getRecipient());
        helper.setSubject(delivery.getSubject());
        helper.setText(smtpBody(delivery), true);
        sender.send(message);
    }

    private String smtpBody(EmailDelivery delivery) throws JsonProcessingException {
        Map<String, String> data = templateData(delivery);
        return switch (delivery.getTemplateKind()) {
            case VERIFICATION -> "<p>你的验证码是：<strong>" + data.get("code") + "</strong></p><p>验证码 " + data.get("minutes") + " 分钟内有效。</p>";
            case ORGANIZATION_APPLICATION -> "<p>用户 " + data.get("applicant") + " 申请加入「" + data.get("organization") + "」。</p><p>申请理由：" + data.get("reason") + "</p>";
            case ORGANIZATION_RESULT -> "<p>你加入「" + data.get("organization") + "」的申请已" + data.get("result") + "。</p><p>处理说明：" + data.get("reason") + "</p>";
            case MEMBERSHIP_CHANGE -> "<p>管理员已调整你的组织成员关系。</p><p>变更原因：" + data.get("reason") + "</p>";
            case ROLE_CHANGE -> "<p>你的账号已" + data.get("action") + "。</p>";
            case ACCOUNT_STATUS_CHANGED -> "<p>" + data.get("displayName") + "，您好：</p><p>你的账户已"
                    + data.get("action") + "。</p><p>操作时间："
                    + data.get("time") + "</p><p>变更原因：" + data.get("reason")
                    + "</p><p>原登录会话已失效。若账户已重新启用，请重新登录；若账户被禁用，请联系超级管理员。</p>";
            case EMAIL_CHANGED -> "<p>你的登录邮箱已变更。如非本人操作，请立即修改密码并联系管理员。</p>";
            case TEST -> "<p>邮件服务配置成功。</p>";
        };
    }

    private JavaMailSenderImpl sender(SmtpSettings settings) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.getHost());
        sender.setPort(settings.getPort());
        sender.setUsername(settings.getUsername());
        sender.setPassword(cryptoService.decrypt(settings.getEncryptedPassword()));
        sender.setDefaultEncoding("UTF-8");
        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.ssl.enable", String.valueOf(settings.isSslEnabled()));
        properties.put("mail.smtp.starttls.enable", String.valueOf(!settings.isSslEnabled()));
        properties.put("mail.smtp.connectiontimeout", "10000");
        properties.put("mail.smtp.timeout", "10000");
        return sender;
    }

    private String shortMessage(Exception e) {
        String value = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return value.substring(0, Math.min(value.length(), 500));
    }
}
