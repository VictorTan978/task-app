package com.taskapp.email;

import java.nio.charset.StandardCharsets;

import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/** Hands one email (text + HTML alternative) to the SMTP server configured by {@code spring.mail.*}. */
@Component
public class MailDelivery {

    private final JavaMailSender mailSender;
    private final EmailProperties properties;

    public MailDelivery(JavaMailSender mailSender, EmailProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    /** @throws MailException if the message can't be built or the server refuses or can't be reached */
    public void send(String to, EmailContent content) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message,
                    MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.from());
            helper.setTo(to);
            helper.setSubject(content.subject());
            helper.setText(content.text(), content.html());
        } catch (MessagingException e) {
            // Only addresses and the subject can fail here; the message (with the link) is never included.
            throw new MailPreparationException("Could not build the email: " + e.getClass().getSimpleName());
        }
        mailSender.send(message);
    }

    /** Builds a frontend link: {@code <APP_BASE_URL><path>?token=<token>}. */
    public String link(String path, String token) {
        return properties.baseUrl() + path + "?token=" + token;
    }

    /** Builds a frontend link without a token: {@code <APP_BASE_URL><path>} (0.13.0: the invitations page). */
    public String page(String path) {
        return properties.baseUrl() + path;
    }

    public EmailProperties properties() {
        return properties;
    }
}
