package com.taskapp.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] API_DOCS_PATHS = {
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"};

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper,
            CsrfTokenRepository csrfTokenRepository, SecurityContextRepository securityContextRepository,
            @Value("${app.api-docs-enabled:false}") boolean apiDocsEnabled) throws Exception {
        ProblemResponseWriter problemWriter = new ProblemResponseWriter(objectMapper);

        http
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login",
                                    "/api/auth/logout").permitAll()
                            .requestMatchers("/error").permitAll();
                    if (apiDocsEnabled) {
                        auth.requestMatchers(HttpMethod.GET, API_DOCS_PATHS).permitAll();
                    }
                    // GET /api/auth/me is "public" in the contract but answers 401 when anonymous.
                    auth.anyRequest().authenticated();
                })
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        // Angular sends the raw cookie value, so no XOR masking (BREACH n/a for this JSON API).
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new ProblemAuthenticationEntryPoint(problemWriter))
                        .accessDeniedHandler(new ProblemAccessDeniedHandler(problemWriter)))
                // Don't create sessions just to remember a request that got a 401.
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.path("/").sameSite("Lax"));
        return repository;
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
