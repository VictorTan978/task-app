package com.taskapp.security.google;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.context.NullSecurityContextRepository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskapp.auth.GoogleAccountService;
import com.taskapp.security.SessionAuthenticator;
import com.taskapp.security.SessionUserRefreshFilter;
import com.taskapp.security.ratelimit.GoogleRateLimiter;

/**
 * Adds "Sign in with Google" to the security filter chain (specs/google-sign-in.md, contract 0.8.0): always the
 * {@link GoogleFlowGuardFilter} (it answers 404 when the feature is off), and Spring Security's OAuth2 / OpenID
 * Connect login when it is on:
 * <ul>
 * <li>start {@code POST /api/auth/oauth2/authorization/google}, callback {@code /api/auth/oauth2/callback/google};
 * scopes {@code openid email profile}; {@code state}, {@code nonce} and PKCE (S256);</li>
 * <li>the pending request in the HTTP session ({@link PendingAuthorizationRequests});</li>
 * <li>only the verified ID token is used: no userinfo call, Google's tokens are discarded
 * ({@link DiscardingAuthorizedClientRepository});</li>
 * <li>the login filter neither changes the session nor stores its authentication
 * ({@link NullAuthenticatedSessionStrategy}, {@link NullSecurityContextRepository}): {@link GoogleCallbackHandler}
 * signs in through {@link SessionAuthenticator} like a password login, or keeps the session for a connect.</li>
 * </ul>
 */
public final class GoogleFlowSecurity {

    private GoogleFlowSecurity() {
    }

    public static void configure(HttpSecurity http, GoogleSignIn google, Supplier<GoogleRateLimiter> rateLimiter,
            Supplier<GoogleAccountService> accounts, Supplier<SessionAuthenticator> sessions,
            ObjectMapper objectMapper) throws Exception {
        PendingAuthorizationRequests pending = new PendingAuthorizationRequests();
        GoogleCallbackHandler handler = new GoogleCallbackHandler(google, accounts, sessions);
        http.addFilterAfter(new GoogleFlowGuardFilter(google, rateLimiter, pending, handler, objectMapper),
                SessionUserRefreshFilter.class);
        if (!google.enabled()) {
            return;
        }
        OAuth2UserService<OidcUserRequest, OidcUser> idTokenOnly =
                request -> new DefaultOidcUser(List.of(), request.getIdToken());
        http.oauth2Login(login -> login
                .clientRegistrationRepository(google.registrations())
                .authorizedClientRepository(new DiscardingAuthorizedClientRepository())
                .authorizationEndpoint(authorization -> authorization
                        .baseUri(GoogleSignIn.AUTHORIZATION_BASE_URI)
                        .authorizationRequestResolver(new GoogleAuthorizationRequestResolver(google.registrations()))
                        .authorizationRequestRepository(pending))
                .redirectionEndpoint(redirection -> redirection.baseUri("/api/auth/oauth2/callback/*"))
                .userInfoEndpoint(userInfo -> userInfo.oidcUserService(idTokenOnly))
                .successHandler(handler)
                .failureHandler(handler)
                .withObjectPostProcessor(new ObjectPostProcessor<OAuth2LoginAuthenticationFilter>() {
                    @Override
                    public <O extends OAuth2LoginAuthenticationFilter> O postProcess(O filter) {
                        filter.setSessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy());
                        filter.setSecurityContextRepository(new NullSecurityContextRepository());
                        return filter;
                    }
                }));
    }
}
