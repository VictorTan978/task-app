package com.taskapp.security;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.RememberMeAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.PortMapper;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationFilter;
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
@Import(SessionConfig.class)
public class SecurityConfig {

    private static final String[] API_DOCS_PATHS = {
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"};

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper,
            CsrfTokenRepository csrfTokenRepository, SecurityContextRepository securityContextRepository,
            ObjectProvider<SessionUserLookup> sessionUserLookup, HashedRememberMeServices rememberMeServices,
            ObjectProvider<SessionAuthenticator> sessionAuthenticator, Clock clock,
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
                            // Email links (specs/email-auth.md): public, CSRF still required. A stale session or
                            // a rejected remember-me cookie only makes the request anonymous, so never a 401.
                            .requestMatchers(HttpMethod.POST, "/api/auth/verify-email",
                                    "/api/auth/forgot-password", "/api/auth/check-reset-token",
                                    "/api/auth/reset-password").permitAll()
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
                // After CSRF, before authorization: a session past its maximum length, ended by "log out other
                // sessions", or of a deleted user becomes anonymous (then 401); a changed email/display name
                // refreshes the session principal.
                .addFilterAfter(new SessionUserRefreshFilter(sessionUserLookup::getObject, securityContextRepository,
                        clock), CsrfCookieFilter.class)
                // Standard remember-me position (after the two filters above, before authorization): no valid
                // session + a valid remember-me cookie = signed in within this request, on any method.
                .addFilterAt(new SessionRememberMeAuthenticationFilter(
                        new ProviderManager(new RememberMeAuthenticationProvider(rememberMeServices.getKey())),
                        rememberMeServices, securityContextRepository, sessionAuthenticator::getObject),
                        RememberMeAuthenticationFilter.class)
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

    /**
     * Remember-me logins (specs/sessions.md). The key is random per startup: it signs nothing persistent (cookies
     * are checked against the hashed database rows), it only ties remember-me authentications to this provider.
     */
    @Bean
    HashedRememberMeServices rememberMeServices(ObjectProvider<RememberMeTokenStore> tokenStore,
            ObjectProvider<SessionUserLookup> sessionUserLookup, Clock clock, RememberMeProperties properties,
            @Value("${app.security.require-https:true}") boolean requireHttps) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return new HashedRememberMeServices(Base64.getEncoder().encodeToString(key), tokenStore::getObject,
                sessionUserLookup::getObject, clock, properties.length(), requireHttps);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
