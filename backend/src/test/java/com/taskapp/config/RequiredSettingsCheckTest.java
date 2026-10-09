package com.taskapp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** AC17 / AC18: no dev secrets outside dev. */
class RequiredSettingsCheckTest {

    private final RequiredSettingsCheck check = new RequiredSettingsCheck();

    /** Same placeholders as application.yml, without any value for them. */
    private static StandardEnvironment envWith(Map<String, Object> props) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return env;
    }

    @Test
    void failsNamingDbPasswordWhenOnlyItIsMissing() {
        var env = envWith(Map.of(
                "spring.datasource.url", "jdbc:mysql://db/taskapp",
                "spring.datasource.username", "app",
                "spring.datasource.password", "${DB_PASSWORD_THAT_IS_NOT_SET}"));

        assertThatThrownBy(() -> check.postProcessEnvironment(env, new SpringApplication()))
                .isInstanceOf(MissingRequiredSettingsException.class)
                .hasMessage("DB_PASSWORD is not set.");
    }

    @Test
    void treatsBlankValueAsMissing() {
        var env = envWith(Map.of(
                "spring.datasource.url", "jdbc:mysql://db/taskapp",
                "spring.datasource.username", " ",
                "spring.datasource.password", ""));

        assertThatThrownBy(() -> check.postProcessEnvironment(env, new SpringApplication()))
                .isInstanceOfSatisfying(MissingRequiredSettingsException.class,
                        e -> assertThat(e.getMissing()).containsExactly("DB_USERNAME", "DB_PASSWORD"));
    }

    @Test
    void passesWhenAllSettingsAreSet() {
        var env = envWith(Map.of(
                "spring.datasource.url", "jdbc:mysql://db/taskapp",
                "spring.datasource.username", "app",
                "spring.datasource.password", "s3cret"));

        check.postProcessEnvironment(env, new SpringApplication());
    }

    @Test
    void failureAnalysisNamesVariableAndSuggestsDevProfile() {
        FailureAnalysis analysis = new MissingRequiredSettingsFailureAnalyzer()
                .analyze(new MissingRequiredSettingsException(java.util.List.of("DB_PASSWORD")));

        assertThat(analysis.getDescription()).startsWith("DB_PASSWORD is not set.");
        assertThat(analysis.getAction())
                .isEqualTo("Set DB_PASSWORD, or run with SPRING_PROFILES_ACTIVE=dev for local development.");
    }

    // --- Real config files (main application.yml / application-dev.yml), real SpringApplication startup.
    // An empty configuration is used so no database is needed; only the environment matters here.

    @Configuration(proxyBeanMethods = false)
    static class Empty {
    }

    private static SpringApplication app() {
        SpringApplication app = new SpringApplication(Empty.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        return app;
    }


    @Test
    void startupWithoutProfileAndWithoutDbSettingsFailsNamingThem() {
        assumeTrue(System.getenv("DB_URL") == null && System.getenv("DB_USERNAME") == null
                && System.getenv("DB_PASSWORD") == null, "DB_* env vars are set in this shell");

        assertThatThrownBy(() -> app().run("--spring.config.location=classpath:application.yml"))
                .isInstanceOfSatisfying(MissingRequiredSettingsException.class,
                        e -> assertThat(e.getMissing()).containsExactly("DB_URL", "DB_USERNAME", "DB_PASSWORD"));
    }

    @Test
    void devProfileUsesLocalDefaultsAndEnablesApiDocs() {
        assumeTrue(System.getenv("DB_URL") == null && System.getenv("DB_USERNAME") == null
                && System.getenv("DB_PASSWORD") == null && System.getenv("API_DOCS_ENABLED") == null,
                "DB_* / API_DOCS_ENABLED env vars are set in this shell");

        try (ConfigurableApplicationContext ctx = app().run(
                "--spring.config.location=classpath:application.yml,classpath:application-dev.yml",
                "--spring.profiles.active=dev")) {
            var env = ctx.getEnvironment();
            assertThat(env.getProperty("spring.datasource.url"))
                    .startsWith("jdbc:mysql://localhost:3306/taskapp?");
            assertThat(env.getProperty("spring.datasource.username")).isEqualTo("taskapp");
            assertThat(env.getProperty("spring.datasource.password")).isEqualTo("taskapp");
            assertThat(env.getProperty("app.api-docs-enabled")).isEqualTo("true");
            assertThat(env.getProperty("springdoc.api-docs.enabled")).isEqualTo("true");
        }
    }

    @Test
    void apiDocsAreOffByDefaultWithoutDevProfile() {
        assumeTrue(System.getenv("API_DOCS_ENABLED") == null, "API_DOCS_ENABLED is set in this shell");

        try (ConfigurableApplicationContext ctx = app().run(
                "--spring.config.location=classpath:application.yml",
                "--DB_URL=jdbc:mysql://db/taskapp", "--DB_USERNAME=app", "--DB_PASSWORD=s3cret")) {
            assertThat(ctx.getEnvironment().getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
        }
    }
}
