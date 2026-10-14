package com.taskapp.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

/**
 * {@code app.session.*} (application.yml, env var {@code SESSION_MAX_LENGTH}).
 *
 * @param maxLength how long a normal session (password login without "remember me", or register) may last from
 *                  its sign-in, however busy it is. The 30-minute idle timeout is separate
 *                  ({@code server.servlet.session.timeout}).
 */
@Validated
@ConfigurationProperties("app.session")
public record SessionProperties(@DefaultValue("12h") @NotNull Duration maxLength) {

    public SessionProperties {
        if (maxLength != null && (maxLength.isZero() || maxLength.isNegative())) {
            throw new IllegalArgumentException("app.session.max-length must be positive");
        }
    }
}
