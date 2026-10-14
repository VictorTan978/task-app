package com.taskapp.security;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Session lifetime settings and the clock the session limits are measured with. Imported by
 * {@link SecurityConfig}, so MockMvc slices that import the security config get it too. Tests replace the clock
 * with a {@code @Primary} one to move time forward.
 */
@Configuration
@EnableConfigurationProperties({SessionProperties.class, RememberMeProperties.class})
public class SessionConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
