package com.taskapp.security.google;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Imported by {@link com.taskapp.security.SecurityConfig}, so MockMvc slices get {@link GoogleSignIn} too. */
@Configuration
@EnableConfigurationProperties(GoogleProperties.class)
public class GoogleSignInConfig {

    @Bean
    GoogleSignIn googleSignIn(GoogleProperties properties, @Value("${app.email.base-url}") String baseUrl) {
        return new GoogleSignIn(properties, baseUrl);
    }
}
