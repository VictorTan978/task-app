package com.taskapp.security.ratelimit;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the auth and email rate limiters and their per-IP checks on {@code POST /api/auth/login}, {@code /register},
 * {@code /forgot-password}, {@code /verify-email}, {@code /check-reset-token} and {@code /reset-password}. The
 * Google flow's per-IP limits ({@link GoogleRateLimiter}) are applied by its security filter instead, because those
 * requests never reach Spring MVC.
 */
@Configuration
@EnableConfigurationProperties({RateLimitProperties.class, EmailRateLimitProperties.class,
        GoogleRateLimitProperties.class, AdminRateLimitProperties.class, InviteRateLimitProperties.class})
public class RateLimitConfig implements WebMvcConfigurer {

    private final AuthRateLimiter authRateLimiter;
    private final EmailRateLimiter emailRateLimiter;
    private final GoogleRateLimiter googleRateLimiter;
    private final AdminRateLimiter adminRateLimiter;
    private final InviteRateLimiter inviteRateLimiter;

    public RateLimitConfig(RateLimitProperties properties, EmailRateLimitProperties emailProperties,
            GoogleRateLimitProperties googleProperties, AdminRateLimitProperties adminProperties,
            InviteRateLimitProperties inviteProperties) {
        this.authRateLimiter = new AuthRateLimiter(properties, Clock.systemUTC());
        this.emailRateLimiter = new EmailRateLimiter(emailProperties, Clock.systemUTC());
        this.googleRateLimiter = new GoogleRateLimiter(googleProperties, Clock.systemUTC());
        this.adminRateLimiter = new AdminRateLimiter(adminProperties, Clock.systemUTC());
        this.inviteRateLimiter = new InviteRateLimiter(inviteProperties, Clock.systemUTC());
    }

    @Bean
    AuthRateLimiter authRateLimiter() {
        return authRateLimiter;
    }

    @Bean
    EmailRateLimiter emailRateLimiter() {
        return emailRateLimiter;
    }

    /** Used by the Google flow filter (com.taskapp.security.google), not by an interceptor. */
    @Bean
    GoogleRateLimiter googleRateLimiter() {
        return googleRateLimiter;
    }

    /** Counted by AdminUserService (per acting admin, after the request is validated). */
    @Bean
    AdminRateLimiter adminRateLimiter() {
        return adminRateLimiter;
    }

    /** Counted by InvitationService (per inviter) and EmailJobs (silent, per recipient). */
    @Bean
    InviteRateLimiter inviteRateLimiter() {
        return inviteRateLimiter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthRateLimitInterceptor(authRateLimiter::acquireLogin))
                .addPathPatterns("/api/auth/login");
        registry.addInterceptor(new AuthRateLimitInterceptor(authRateLimiter::acquireRegister))
                .addPathPatterns("/api/auth/register");
        registry.addInterceptor(new AuthRateLimitInterceptor(emailRateLimiter::acquireForgotPassword))
                .addPathPatterns("/api/auth/forgot-password");
        registry.addInterceptor(new AuthRateLimitInterceptor(emailRateLimiter::acquireVerifyEmail))
                .addPathPatterns("/api/auth/verify-email");
        registry.addInterceptor(new AuthRateLimitInterceptor(emailRateLimiter::acquireCheckResetToken))
                .addPathPatterns("/api/auth/check-reset-token");
        registry.addInterceptor(new AuthRateLimitInterceptor(emailRateLimiter::acquireResetPassword))
                .addPathPatterns("/api/auth/reset-password");
    }
}
