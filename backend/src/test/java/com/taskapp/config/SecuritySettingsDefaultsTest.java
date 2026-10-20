package com.taskapp.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Pins the HTTPS / proxy settings in the shipped config files, resolved without the test overrides in
 * src/test/resources/application.properties and without the machine's environment variables.
 */
class SecuritySettingsDefaultsTest {

    private static final String BOOT_DEFAULT_INTERNAL_PROXIES =
            new TomcatServerProperties().getRemoteip().getInternalProxies();

    private static StandardEnvironment environment(Map<String, Object> envVars, String... ymlFiles) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("env", envVars));
        // Later files take precedence, like a profile-specific file over application.yml.
        for (String file : ymlFiles) {
            for (PropertySource<?> source : new YamlPropertySourceLoader().load(file, new ClassPathResource(file))) {
                env.getPropertySources().addAfter("env", source);
            }
        }
        return env;
    }

    @Test
    void productionWithoutAnySettingRequiresHttpsAndTrustsOnlyPrivateProxies() throws IOException {
        StandardEnvironment env = environment(Map.of(), "application.yml");

        assertThat(env.getProperty("app.security.require-https", Boolean.class)).isTrue();
        assertThat(env.getProperty("server.servlet.session.cookie.secure", Boolean.class)).isTrue();
        assertThat(env.getProperty("server.forward-headers-strategy")).isEqualTo("native");
        assertThat(env.getProperty("server.tomcat.remoteip.internal-proxies")).isEqualTo(BOOT_DEFAULT_INTERNAL_PROXIES);
    }

    @Test
    void productionCanBeConfiguredThroughEnvironmentVariables() throws IOException {
        StandardEnvironment env = environment(
                Map.of("REQUIRE_HTTPS", "false", "TRUSTED_PROXIES", "10\\.0\\.0\\.5"), "application.yml");

        assertThat(env.getProperty("app.security.require-https", Boolean.class)).isFalse();
        assertThat(env.getProperty("server.servlet.session.cookie.secure", Boolean.class)).isFalse();
        assertThat(env.getProperty("server.tomcat.remoteip.internal-proxies")).isEqualTo("10\\.0\\.0\\.5");
    }

    @Test
    void devProfileTurnsHttpsOnlyOffEvenWhenTheProductionVariableIsSet() throws IOException {
        StandardEnvironment env = environment(Map.of("REQUIRE_HTTPS", "true"), "application.yml", "application-dev.yml");

        assertThat(env.getProperty("app.security.require-https", Boolean.class)).isFalse();
        assertThat(env.getProperty("server.servlet.session.cookie.secure", Boolean.class)).isFalse();
    }
}
