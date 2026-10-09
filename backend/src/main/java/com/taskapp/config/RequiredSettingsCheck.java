package com.taskapp.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Secure by default: application.yml has no database defaults, so outside the {@code dev} profile the
 * app must be given its database settings. Without this check a missing variable surfaces later as an
 * unrelated error (e.g. "'url' must start with \"jdbc\"") instead of naming the variable.
 *
 * <p>Runs after the config files (including profile-specific ones) are loaded. A setting counts as
 * missing when its placeholder cannot be resolved or resolves to a blank value.
 * Registered in {@code META-INF/spring.factories}; reported by {@link MissingRequiredSettingsFailureAnalyzer}.
 */
public class RequiredSettingsCheck implements EnvironmentPostProcessor, Ordered {

    /** Spring property -> environment variable that application.yml reads it from. */
    static final Map<String, String> REQUIRED = new LinkedHashMap<>();
    static {
        REQUIRED.put("spring.datasource.url", "DB_URL");
        REQUIRED.put("spring.datasource.username", "DB_USERNAME");
        REQUIRED.put("spring.datasource.password", "DB_PASSWORD");
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        List<String> missing = new ArrayList<>();
        REQUIRED.forEach((property, envVar) -> {
            if (!isSet(environment, property)) {
                missing.add(envVar);
            }
        });
        if (!missing.isEmpty()) {
            throw new MissingRequiredSettingsException(missing);
        }
    }

    private static boolean isSet(ConfigurableEnvironment environment, String property) {
        try {
            String value = environment.getProperty(property);
            return value != null && !value.isBlank();
        } catch (IllegalArgumentException unresolvablePlaceholder) {
            return false;
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
