package com.taskapp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import com.taskapp.security.RememberMeProperties;
import com.taskapp.security.SessionProperties;

/**
 * specs/sessions.md AC8: idle 30m, maximum session length 12h ({@code SESSION_MAX_LENGTH}) and remember-me
 * length 30d ({@code REMEMBER_ME_LENGTH}), resolved from the shipped application.yml without the machine's
 * environment.
 */
class SessionSettingsTest {

    private static StandardEnvironment environment(Map<String, Object> envVars) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("env", envVars));
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))) {
            env.getPropertySources().addAfter("env", source);
        }
        return env;
    }

    private static SessionProperties session(StandardEnvironment env) {
        return Binder.get(env).bind("app.session", SessionProperties.class).get();
    }

    private static RememberMeProperties rememberMe(StandardEnvironment env) {
        return Binder.get(env).bind("app.remember-me", RememberMeProperties.class).get();
    }

    @Test
    void defaultsAre30MinutesIdle12HoursMaximumAnd30DaysRememberMe() throws IOException {
        StandardEnvironment env = environment(Map.of());

        assertThat(Binder.get(env).bind("server.servlet.session.timeout", Duration.class).get())
                .isEqualTo(Duration.ofMinutes(30));
        assertThat(session(env).maxLength()).isEqualTo(Duration.ofHours(12));
        assertThat(rememberMe(env).length()).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void bothLimitsAreSetThroughEnvironmentVariables() throws IOException {
        StandardEnvironment env = environment(Map.of("SESSION_MAX_LENGTH", "2m", "REMEMBER_ME_LENGTH", "5m"));

        assertThat(session(env).maxLength()).isEqualTo(Duration.ofMinutes(2));
        assertThat(rememberMe(env).length()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void zeroOrNegativeLimitsAreRejectedAtStartup() throws IOException {
        assertThatThrownBy(() -> session(environment(Map.of("SESSION_MAX_LENGTH", "0s"))))
                .isInstanceOf(BindException.class);
        assertThatThrownBy(() -> rememberMe(environment(Map.of("REMEMBER_ME_LENGTH", "-1d"))))
                .isInstanceOf(BindException.class);
    }
}
