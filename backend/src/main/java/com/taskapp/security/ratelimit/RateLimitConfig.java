package com.taskapp.security.ratelimit;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Wires the auth rate limiter and its per-IP checks on {@code POST /api/auth/login} and {@code /register}. */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig implements WebMvcConfigurer {

    private final AuthRateLimiter authRateLimiter;

    public RateLimitConfig(RateLimitProperties properties) {
        this.authRateLimiter = new AuthRateLimiter(properties, Clock.systemUTC());
    }

    @Bean
    AuthRateLimiter authRateLimiter() {
        return authRateLimiter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthRateLimitInterceptor(authRateLimiter::acquireLogin))
                .addPathPatterns("/api/auth/login");
        registry.addInterceptor(new AuthRateLimitInterceptor(authRateLimiter::acquireRegister))
                .addPathPatterns("/api/auth/register");
    }
}
