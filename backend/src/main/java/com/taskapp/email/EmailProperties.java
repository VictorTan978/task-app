package com.taskapp.email;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * {@code app.email.*} (application.yml). The SMTP server itself is Spring Boot's {@code spring.mail.*}
 * ({@code MAIL_HOST}, {@code MAIL_PORT}, {@code MAIL_USERNAME}, {@code MAIL_PASSWORD}).
 *
 * @param baseUrl                   frontend URL the links point to ({@code APP_BASE_URL}), e.g.
 *                                  {@code https://tasks.example.com}; a trailing slash is ignored
 * @param from                      sender, e.g. {@code Task App <no-reply@taskapp.local>} ({@code MAIL_FROM})
 * @param verifyEmailLinkLifetime   how long a confirmation link works ({@code VERIFY_EMAIL_LINK_LIFETIME})
 * @param resetPasswordLinkLifetime how long a reset link works ({@code RESET_PASSWORD_LINK_LIFETIME})
 */
@Validated
@ConfigurationProperties("app.email")
public record EmailProperties(
        @NotBlank String baseUrl,
        @NotBlank String from,
        @DefaultValue("24h") @NotNull Duration verifyEmailLinkLifetime,
        @DefaultValue("1h") @NotNull Duration resetPasswordLinkLifetime) {

    public EmailProperties {
        if (baseUrl != null) {
            baseUrl = baseUrl.strip();
            while (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
        }
        requireAtLeastOneSecond(verifyEmailLinkLifetime, "verify-email-link-lifetime");
        requireAtLeastOneSecond(resetPasswordLinkLifetime, "reset-password-link-lifetime");
    }

    private static void requireAtLeastOneSecond(Duration lifetime, String name) {
        if (lifetime != null && lifetime.getSeconds() < 1) {
            throw new IllegalArgumentException("app.email." + name + " must be at least 1 second");
        }
    }
}
