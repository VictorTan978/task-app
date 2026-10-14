package com.taskapp.email;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Enables {@link EmailProperties}. The {@code JavaMailSender} comes from Spring Boot ({@code spring.mail.host}). */
@Configuration
@EnableConfigurationProperties(EmailProperties.class)
public class EmailConfig {
}
