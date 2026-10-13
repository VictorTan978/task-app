package com.taskapp.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.PortMapper;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] API_DOCS_PATHS = {
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"};

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper,
            CsrfTokenRepository csrfTokenRepository, SecurityContextRepository securityContextRepository,
            ObjectProvider<SessionUserLookup> sessionUserLookup,
            @Value("${app.api-docs-enabled:false}") boolean apiDocsEnabled,
            @Value("${app.security.require-https:true}") boolean requireHttps) throws Exception {
        ProblemResponseWriter problemWriter = new ProblemResponseWriter(objectMapper);

        if (requireHttps) {
            // "Plain HTTP" = request.getScheme() is http, i.e. it came over plain HTTP directly, or a trusted
            // proxy sent X-Forwarded-Proto: http (see server.forward-headers-strategy in application.yml).
            // GET /api/health is exempt: load balancers usually probe the app directly over plain HTTP and
            // treat a 302 as unhealthy; the endpoint returns only {"status":"UP"}.
            http.setSharedObject(PortMapper.class, DEFAULT_HTTPS_PORT);
            http.redirectToHttps(https -> https.requestMatchers(new NegatedRequestMatcher(
                    PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/health"))));
        }

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
                // After CSRF, before authorization: a deleted user's session becomes anonymous (then 401),
                // a changed email/display name refreshes the session principal.
                .addFilterAfter(new SessionUserRefreshFilter(sessionUserLookup::getObject, securityContextRepository),
                        CsrfCookieFilter.class)
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

    /**
     * Redirect targets use the default HTTPS port (443, so no port in the URL), whatever port the plain-HTTP
     * request arrived on. Spring's default mapper only knows 80->443 and 8080->8443 and fails (500) on others.
     * -1 makes the redirect URL omit the port.
     */
    private static final PortMapper DEFAULT_HTTPS_PORT = new PortMapper() {
        @Override
        public Integer lookupHttpPort(Integer httpsPort) {
            return null;
        }

        @Override
        public Integer lookupHttpsPort(Integer httpPort) {
            return -1;
        }
    };

    /** With require-https the cookie is always Secure; otherwise Secure follows request.isSecure() (the default). */
    @Bean
    CsrfTokenRepository csrfTokenRepository(@Value("${app.security.require-https:true}") boolean requireHttps) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> {
            cookie.path("/").sameSite("Lax");
            if (requireHttps) {
                cookie.secure(true);
            }
        });
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
