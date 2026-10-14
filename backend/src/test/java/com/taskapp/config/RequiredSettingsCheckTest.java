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

/**
 * AC17 / AC18: no dev secrets outside dev. AC13: separate migration user is required too. specs/email-auth.md:
 * MAIL_HOST, MAIL_FROM and APP_BASE_URL are required too, and APP_BASE_URL must be https when REQUIRE_HTTPS is on.
 */
class RequiredSettingsCheckTest {

    private final RequiredSettingsCheck check = new RequiredSettingsCheck();

    /** Valid mail settings (specs/email-auth.md), so the DB tests below only test the DB settings. */
    private static final Map<String, Object> MAIL = Map.of(
            "spring.mail.host", "smtp.example.com",
            "app.email.from", "Task App <no-reply@example.com>",
            "app.email.base-url", "https://tasks.example.com");

    /** Valid database settings, so the mail tests below only test the mail settings. */
    private static final Map<String, Object> DB = Map.of(
            "spring.datasource.url", "jdbc:mysql://db/taskapp",
            "spring.datasource.username", "app",
            "spring.datasource.password", "s3cret",
            "spring.flyway.user", "migrator",
            "spring.flyway.password", "m1grate");

    /** {@code props} plus {@link #MAIL}; {@code props} wins. */
    private static StandardEnvironment envWith(Map<String, Object> props) {
        java.util.Map<String, Object> all = new java.util.HashMap<>(MAIL);
        all.putAll(props);
        return envWithOnly(all);
    }

    private static StandardEnvironment envWithOnly(Map<String, Object> props) {
        StandardEnvironment env = new StandardEnvironment();
        // Independent of the machine: REQUIRE_HTTPS or MAIL_* set in the shell must not change the result.
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return env;
    }

    private static Map<String, Object> dbAnd(Object... keyValues) {
        java.util.Map<String, Object> all = new java.util.HashMap<>(DB);
        for (int i = 0; i < keyValues.length; i += 2) {
            all.put((String) keyValues[i], keyValues[i + 1]);
        }
        return all;
    }

    // --- specs/email-auth.md: MAIL_HOST, MAIL_FROM and APP_BASE_URL are required; https rule

    @Test
    void failsNamingEveryMissingMailSetting() {
        var env = envWithOnly(dbAnd("spring.mail.host", "${MAIL_HOST_THAT_IS_NOT_SET}",
                "app.email.from", " "));

        assertThatThrownBy(() -> check.postProcessEnvironment(env, new SpringApplication()))
                .isInstanceOfSatisfying(MissingRequiredSettingsException.class,
                        e -> assertThat(e.getMissing()).containsExactly("MAIL_HOST", "MAIL_FROM", "APP_BASE_URL"));
    }

    @Test
    void refusesPlainHttpBaseUrlWhenHttpsIsRequired() {
        var env = envWith(dbAnd("app.security.require-https", "true",
                "app.email.base-url", "http://tasks.example.com"));

        assertThatThrownBy(() -> check.postProcessEnvironment(env, new SpringApplication()))
                .isInstanceOfSatisfying(InvalidSettingException.class, e -> {
                    assertThat(e.getSetting()).isEqualTo("APP_BASE_URL");
                    assertThat(e.getMessage()).startsWith("APP_BASE_URL must start with https://");
                });
    }

    @Test
    void httpsIsRequiredWhenRequireHttpsIsNotSetBecauseThatIsTheProductionDefault() {
        var env = envWith(dbAnd("app.email.base-url", "http://tasks.example.com"));

        assertThatThrownBy(() -> check.postProcessEnvironment(env, new SpringApplication()))
                .isInstanceOf(InvalidSettingException.class);
    }

    @Test
    void acceptsHttpsBaseUrlWhenHttpsIsRequired() {
        check.postProcessEnvironment(envWith(dbAnd("app.security.require-https", "true",
                "app.email.base-url", "HTTPS://tasks.example.com/")), new SpringApplication());
    }

    @Test
    void acceptsPlainHttpBaseUrlWhenHttpsIsNotRequired() {
        check.postProcessEnvironment(envWith(dbAnd("app.security.require-https", "false",
                "app.email.base-url", "http://localhost:4200")), new SpringApplication());
    }

    @Test
    void invalidSettingFailureAnalysisNamesTheSetting() {
        FailureAnalysis analysis = new InvalidSettingFailureAnalyzer()
                .analyze(new InvalidSettingException("APP_BASE_URL", "must start with https://"));

        assertThat(analysis.getDescription()).isEqualTo("APP_BASE_URL must start with https://.");
        assertThat(analysis.getAction())
                .isEqualTo("Fix APP_BASE_URL, or run with SPRING_PROFILES_ACTIVE=dev for local development.");
    }

    @Test
    void failsNamingDbPasswordWhenOnlyItIsMissing() {
        var env = envWith(Map.of(
                "spring.datasource.url", "jdbc:mysql://db/taskapp",
                "spring.datasource.username", "app",
                "spring.datasource.password", "${DB_PASSWORD_THAT_IS_NOT_SET}",
                "spring.flyway.user", "migrator",
                "spring.flyway.password", "m1grate"));

        assertThatThrownBy(() -> check.postProcessEnvironment(env, new SpringApplication()))
                .isInstanceOf(MissingRequiredSettingsException.class)
                .hasMessage("DB_PASSWORD is not set.");
    }

    @Test
    void treatsBlankValueAsMissing() {
        var env = envWith(Map.of(
                "spring.datasource.url", "jdbc:mysql://db/taskapp",
                "spring.datasource.username", " ",
                "spring.datasource.password", "",
                "spring.flyway.user", "migrator",
                "spring.flyway.password", "m1grate"));

        assertThatThrownBy(() -> check.postProcessEnvironment(env, new SpringApplication()))
                .isInstanceOfSatisfying(MissingRequiredSettingsException.class,
                        e -> assertThat(e.getMissing()).containsExactly("DB_USERNAME", "DB_PASSWORD"));
    }

    @Test
    void passesWhenAllSettingsAreSet() {
        var env = envWith(Map.of(
                "spring.datasource.url", "jdbc:mysql://db/taskapp",
                "spring.datasource.username", "app",
                "spring.datasource.password", "s3cret",
                "spring.flyway.user", "migrator",
                "spring.flyway.password", "m1grate"));

        check.postProcessEnvironment(env, new SpringApplication());
    }

    @Test
    void failsNamingMigrationUserSettingsWhenMissing() {
        var env = envWith(Map.of(
                "spring.datasource.url", "jdbc:mysql://db/taskapp",
                "spring.datasource.username", "app",
                "spring.datasource.password", "s3cret",
                "spring.flyway.user", "${DB_MIGRATION_USERNAME_THAT_IS_NOT_SET}",
                "spring.flyway.password", " "));

        assertThatThrownBy(() -> check.postProcessEnvironment(env, new SpringApplication()))
                .isInstanceOf(MissingRequiredSettingsException.class)
                .hasMessage("DB_MIGRATION_USERNAME, DB_MIGRATION_PASSWORD are not set.");
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

    private static boolean noDbEnvVars() {
        return java.util.stream.Stream.of("DB_URL", "DB_USERNAME", "DB_PASSWORD",
                "DB_MIGRATION_USERNAME", "DB_MIGRATION_PASSWORD", "MAIL_HOST", "MAIL_PORT", "MAIL_FROM",
                "APP_BASE_URL", "REQUIRE_HTTPS").allMatch(name -> System.getenv(name) == null);
    }

    private static final String[] PRODUCTION_SETTINGS = {
            "--DB_URL=jdbc:mysql://db/taskapp", "--DB_USERNAME=app", "--DB_PASSWORD=s3cret",
            "--DB_MIGRATION_USERNAME=migrator", "--DB_MIGRATION_PASSWORD=m1grate",
            "--MAIL_HOST=smtp.example.com", "--MAIL_FROM=Task App <no-reply@example.com>",
            "--APP_BASE_URL=https://tasks.example.com"};

    /** {@link #PRODUCTION_SETTINGS} with {@code overrides} replacing same-named ones (a repeated option is joined). */
    private static String[] productionArgs(String... overrides) {
        java.util.Map<String, String> args = new java.util.LinkedHashMap<>();
        args.put("--spring.config.location", "--spring.config.location=classpath:application.yml");
        for (String arg : PRODUCTION_SETTINGS) {
            args.put(arg.substring(0, arg.indexOf('=')), arg);
        }
        for (String arg : overrides) {
            args.put(arg.substring(0, arg.indexOf('=')), arg);
        }
        return args.values().toArray(String[]::new);
    }

    private static SpringApplication app() {
        SpringApplication app = new SpringApplication(Empty.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        return app;
    }


    @Test
    void startupWithoutProfileAndWithoutDbSettingsFailsNamingThem() {
        assumeTrue(noDbEnvVars(), "DB_* env vars are set in this shell");

        assertThatThrownBy(() -> app().run("--spring.config.location=classpath:application.yml"))
                .isInstanceOfSatisfying(MissingRequiredSettingsException.class,
                        e -> assertThat(e.getMissing()).containsExactly("DB_URL", "DB_USERNAME", "DB_PASSWORD",
                                "DB_MIGRATION_USERNAME", "DB_MIGRATION_PASSWORD", "MAIL_HOST", "MAIL_FROM",
                                "APP_BASE_URL"));
    }

    @Test
    void productionStartupRefusesPlainHttpBaseUrl() {
        assumeTrue(noDbEnvVars(), "DB_* / MAIL_* / APP_BASE_URL / REQUIRE_HTTPS env vars are set in this shell");

        assertThatThrownBy(() -> app().run(productionArgs("--APP_BASE_URL=http://tasks.example.com")))
                .isInstanceOfSatisfying(InvalidSettingException.class,
                        e -> assertThat(e.getSetting()).isEqualTo("APP_BASE_URL"));
    }

    @Test
    void productionStartupAcceptsPlainHttpBaseUrlWhenHttpsIsTurnedOff() {
        assumeTrue(noDbEnvVars(), "DB_* / MAIL_* / APP_BASE_URL / REQUIRE_HTTPS env vars are set in this shell");

        try (ConfigurableApplicationContext ctx = app().run(
                productionArgs("--APP_BASE_URL=http://tasks.internal", "--REQUIRE_HTTPS=false"))) {
            assertThat(ctx.getEnvironment().getProperty("app.email.base-url")).isEqualTo("http://tasks.internal");
        }
    }

    @Test
    void productionMailSettingsComeFromEnvironmentVariablesWithSecureSmtpDefaults() {
        assumeTrue(noDbEnvVars(), "DB_* / MAIL_* / APP_BASE_URL / REQUIRE_HTTPS env vars are set in this shell");

        try (ConfigurableApplicationContext ctx = app().run(productionArgs())) {
            var env = ctx.getEnvironment();
            assertThat(env.getProperty("spring.mail.host")).isEqualTo("smtp.example.com");
            assertThat(env.getProperty("spring.mail.port")).isEqualTo("587");
            assertThat(env.getProperty("spring.mail.properties[mail.smtp.auth]")).isEqualTo("true");
            assertThat(env.getProperty("spring.mail.properties[mail.smtp.starttls.required]")).isEqualTo("true");
            assertThat(env.getProperty("app.email.from")).isEqualTo("Task App <no-reply@example.com>");
            assertThat(env.getProperty("app.email.base-url")).isEqualTo("https://tasks.example.com");
            assertThat(env.getProperty("app.email.verify-email-link-lifetime")).isEqualTo("24h");
            assertThat(env.getProperty("app.email.reset-password-link-lifetime")).isEqualTo("1h");
        }
    }

    @Test
    void devProfileUsesLocalDefaultsAndEnablesApiDocs() {
        assumeTrue(noDbEnvVars() && System.getenv("API_DOCS_ENABLED") == null,
                "DB_* / API_DOCS_ENABLED env vars are set in this shell");

        try (ConfigurableApplicationContext ctx = app().run(
                "--spring.config.location=classpath:application.yml,classpath:application-dev.yml",
                "--spring.profiles.active=dev")) {
            var env = ctx.getEnvironment();
            assertThat(env.getProperty("spring.datasource.url"))
                    .startsWith("jdbc:mysql://localhost:3306/taskapp?");
            // AC13: the app uses the restricted user, Flyway the migration user (docker-compose "db" service).
            assertThat(env.getProperty("spring.datasource.username")).isEqualTo("taskapp_app");
            assertThat(env.getProperty("spring.datasource.password")).isEqualTo("taskapp_app");
            assertThat(env.getProperty("spring.flyway.user")).isEqualTo("taskapp");
            assertThat(env.getProperty("spring.flyway.password")).isEqualTo("taskapp");
            assertThat(env.getProperty("app.api-docs-enabled")).isEqualTo("true");
            assertThat(env.getProperty("springdoc.api-docs.enabled")).isEqualTo("true");
            // specs/email-auth.md AC13/AC14: Mailpit, the default sender and the Angular dev server.
            assertThat(env.getProperty("spring.mail.host")).isEqualTo("localhost");
            assertThat(env.getProperty("spring.mail.port")).isEqualTo("1025");
            assertThat(env.getProperty("spring.mail.properties[mail.smtp.auth]")).isEqualTo("false");
            assertThat(env.getProperty("spring.mail.properties[mail.smtp.starttls.required]")).isEqualTo("false");
            assertThat(env.getProperty("app.email.from")).isEqualTo("Task App <no-reply@taskapp.local>");
            assertThat(env.getProperty("app.email.base-url")).isEqualTo("http://localhost:4200");
        }
    }

    @Test
    void apiDocsAreOffByDefaultWithoutDevProfile() {
        assumeTrue(System.getenv("API_DOCS_ENABLED") == null, "API_DOCS_ENABLED is set in this shell");

        try (ConfigurableApplicationContext ctx = app().run(productionArgs())) {
            assertThat(ctx.getEnvironment().getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
        }
    }
}
