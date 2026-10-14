package com.taskapp.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

/**
 * {@code app.remember-me.*} (application.yml, env var {@code REMEMBER_ME_LENGTH}).
 *
 * @param length how long a remember-me login lasts from the login with {@code rememberMe: true}. Never extended by
 *               use; a remember-me session also ends at that time.
 */
@Validated
@ConfigurationProperties("app.remember-me")
public record RememberMeProperties(@DefaultValue("30d") @NotNull Duration length) {

    public RememberMeProperties {
        if (length != null && length.getSeconds() < 1) {
            throw new IllegalArgumentException("app.remember-me.length must be at least 1 second");
        }
    }
}
